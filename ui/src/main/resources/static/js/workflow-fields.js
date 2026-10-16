/* Pure helpers for the workflow launch dialog (no DOM), also loadable from Node for tests. */
(function (root) {
    'use strict';

    function len(value) {
        return String(value == null ? '' : value).trim().length;
    }

    /** Message for an input value that breaks its field's rules (required/min/max), or null. */
    function fieldError(field, value) {
        var n = len(value);
        if (n === 0) return field.required !== false ? 'This field is required.' : null;
        if (field.minLength != null && n < field.minLength) {
            return 'Enter at least ' + field.minLength + ' characters (' + n + ' so far).';
        }
        if (field.maxLength != null && n > field.maxLength) {
            return 'Use at most ' + field.maxLength + ' characters (' + (n - field.maxLength) + ' too many).';
        }
        return null;
    }

    /** Live counter text, or '' when the field has no length limits. */
    function counterText(field, value) {
        var n = len(value);
        if (field.maxLength != null) {
            return n + ' / ' + field.maxLength + (field.minLength != null ? ' (min ' + field.minLength + ')' : '');
        }
        return field.minLength != null ? n + ' characters (min ' + field.minLength + ')' : '';
    }

    /** True when the typed text equals the expected value exactly (case-sensitive, trimmed). */
    function confirmMatches(typed, expected) {
        var want = String(expected == null ? '' : expected).trim();
        return want !== '' && String(typed == null ? '' : typed).trim() === want;
    }

    /** BLOCKED plan steps that carry messages: [{step: name, messages: [..]}]. */
    function blockedItems(plan) {
        return ((plan && plan.steps) || []).filter(function (s) {
            return s.status === 'BLOCKED';
        }).map(function (s) {
            return {step: s.displayName || s.stepId, messages: s.messages || []};
        });
    }

    /** Readable text for a failed API answer {status, data}. */
    function errorText(status, data, fallback) {
        var msg = data && typeof data.message === 'string' ? data.message.trim() : '';
        if (status === 403) return 'You are not allowed to do this for this item.';
        if (status === 409) return msg && msg.toLowerCase() !== 'conflict' ? msg : 'This item is locked by another operation in progress. Try again when it finishes.';
        if (status === 404) return 'This workflow or item was not found.';
        return msg || fallback;
    }

    var api = {fieldError: fieldError, counterText: counterText, confirmMatches: confirmMatches,
        blockedItems: blockedItems, errorText: errorText};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.DmsWorkflowFields = api;
})(typeof window !== 'undefined' ? window : this);
