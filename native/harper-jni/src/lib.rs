//! Duet's bridge to Harper (https://github.com/Automattic/harper), an offline, rule-based
//! English grammar checker. The keyboard hands it the sentence before the cursor and gets
//! back the problems Harper sees, each with the fixes it proposes.
//!
//! The JNI surface is four static methods on `com.kinetica.keyboard.grammar.HarperNative`:
//! `create(dialect)`, `lint(handle, text)`, `destroy(handle)` and `version()`. Results cross
//! as one flat string ([encode]) so the Kotlin side needs no JSON parser and the format is
//! testable on the JVM.

use std::sync::Arc;

use harper_core::linting::{Lint, LintGroup, LintKind, Linter, Suggestion};
use harper_core::spell::FstDictionary;
use harper_core::{Dialect, Document};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};

/// Field and record separators of the encoded result. Unit separator (0x1F) splits the
/// suggestions of one lint; neither control character can come from typed text once
/// [clean] has run.
const FIELD: char = '\t';
const RECORD: char = '\n';
const SUGGESTION: char = '\u{1F}';

/// One checker per keyboard: the curated dictionary and rule set, built once.
pub struct Checker {
    group: LintGroup,
    dictionary: Arc<FstDictionary>,
}

impl Checker {
    /// `dialect`: 0 American, 1 British, 2 Canadian, 3 Australian, 4 Indian.
    pub fn new(dialect: i32) -> Self {
        let dialect = match dialect {
            1 => Dialect::British,
            2 => Dialect::Canadian,
            3 => Dialect::Australian,
            4 => Dialect::Indian,
            _ => Dialect::American,
        };
        let dictionary = FstDictionary::curated();
        // new_curated turns on Harper's default rule set, the same one its editor plugins use.
        let group = LintGroup::new_curated(dictionary.clone(), dialect);
        Self { group, dictionary }
    }

    /// The lints Harper raises on [text], spelling excluded: Duet's own dictionaries and
    /// learned words decide spelling, and Harper's word list would flag every name and
    /// every word the owner taught the keyboard.
    pub fn lint(&mut self, text: &str) -> Vec<Lint> {
        let document = Document::new_plain_english(text, self.dictionary.as_ref());
        let mut lints = self.group.lint(&document);
        lints.retain(|l| l.lint_kind != LintKind::Spelling && !l.suggestions.is_empty());
        lints.sort_by_key(|l| (l.span.start, l.priority));
        lints
    }
}

/// One record per lint, fields tab-separated:
/// `start end kind priority message suggestions`, where offsets are UTF-16 code units
/// (Java string indices, not Harper's char indices) and each suggestion is `R<text>`
/// (replace the span), `D` (delete it) or `I<text>` (insert after it).
pub fn encode(text: &str, lints: &[Lint]) -> String {
    // utf16[i] is the UTF-16 offset of char i; one extra entry for the end of the text.
    let mut utf16 = Vec::with_capacity(text.len() + 1);
    let mut at = 0usize;
    for c in text.chars() {
        utf16.push(at);
        at += c.len_utf16();
    }
    utf16.push(at);
    let to16 = |i: usize| utf16.get(i).copied().unwrap_or(at);

    let mut out = String::new();
    for lint in lints {
        if !out.is_empty() {
            out.push(RECORD);
        }
        out.push_str(&to16(lint.span.start).to_string());
        out.push(FIELD);
        out.push_str(&to16(lint.span.end).to_string());
        out.push(FIELD);
        out.push_str(&lint.lint_kind.to_string_key());
        out.push(FIELD);
        out.push_str(&lint.priority.to_string());
        out.push(FIELD);
        out.push_str(&clean(&lint.message));
        out.push(FIELD);
        for (i, s) in lint.suggestions.iter().enumerate() {
            if i > 0 {
                out.push(SUGGESTION);
            }
            match s {
                Suggestion::ReplaceWith(chars) => {
                    out.push('R');
                    out.push_str(&clean(&chars.iter().collect::<String>()));
                }
                Suggestion::Remove => out.push('D'),
                Suggestion::InsertAfter(chars) => {
                    out.push('I');
                    out.push_str(&clean(&chars.iter().collect::<String>()));
                }
            }
        }
    }
    out
}

/// [s] with the separators turned into spaces.
fn clean(s: &str) -> String {
    s.chars()
        .map(|c| if c == FIELD || c == RECORD || c == SUGGESTION || c == '\r' { ' ' } else { c })
        .collect()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetica_keyboard_grammar_HarperNative_create(
    _env: JNIEnv,
    _class: JClass,
    dialect: jint,
) -> jlong {
    Box::into_raw(Box::new(Checker::new(dialect))) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetica_keyboard_grammar_HarperNative_lint(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
) -> jstring {
    let encoded = if handle == 0 {
        String::new()
    } else {
        match env.get_string(&text) {
            Ok(text) => {
                let text: String = text.into();
                // SAFETY: handle came from create() and is used from one thread at a time
                // (HarperGrammar serializes every call).
                let checker = unsafe { &mut *(handle as *mut Checker) };
                encode(&text, &checker.lint(&text))
            }
            Err(_) => String::new(),
        }
    };
    env.new_string(encoded).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetica_keyboard_grammar_HarperNative_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: handle came from create() and is destroyed once.
        drop(unsafe { Box::from_raw(handle as *mut Checker) });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetica_keyboard_grammar_HarperNative_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(HARPER_VERSION).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// The harper-core release this library is built against (pinned in Cargo.toml).
pub const HARPER_VERSION: &str = "2.13.0";

#[cfg(test)]
mod tests {
    use super::*;

    fn fixes(text: &str) -> String {
        let mut c = Checker::new(0);
        encode(text, &c.lint(text))
    }

    #[test]
    fn flags_a_doubled_word_with_a_fix() {
        let out = fixes("I went to the the store.");
        assert!(!out.is_empty(), "no lint for a doubled word");
    }

    #[test]
    fn clean_sentence_has_no_lints() {
        assert_eq!(fixes("I went to the store yesterday."), "");
    }

    #[test]
    fn spelling_is_left_to_duet() {
        assert_eq!(fixes("Jasonn typed it."), "");
    }

    #[test]
    fn offsets_are_utf16() {
        // The emoji is one char but two UTF-16 units, so the doubled word starts at 3 in Java.
        let text = "😀 the the cat";
        let out = fixes(text);
        let start: usize = out.split(FIELD).next().unwrap().parse().unwrap();
        let java: Vec<u16> = text.encode_utf16().collect();
        assert_eq!(String::from_utf16(&java[start..start + 3]).unwrap(), "the");
    }
}
