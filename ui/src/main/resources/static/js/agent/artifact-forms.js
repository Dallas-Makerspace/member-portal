import {html} from 'https://esm.sh/lit@3.3.0';

const ACTIVITY_TYPES = ['lecture', 'reading', 'video', 'discussion', 'assignment', 'quiz', 'lab', 'other'];
const QUESTION_TYPES = {
    multiple_choice: 'Multiple choice',
    true_false: 'True / false',
    short_answer: 'Short answer',
    matching: 'Matching',
};

const move = (list, i, by) => {
    const j = i + by;
    if (j < 0 || j >= list.length) return;
    [list[i], list[j]] = [list[j], list[i]];
};

/** A new question of [type] with sensible blanks. */
export function blankQuestion(type) {
    const q = {type, text: '', feedback: ''};
    if (type === 'multiple_choice') q.options = [{text: '', correct: true}, {text: '', correct: false}];
    if (type === 'true_false') q.options = [{text: 'True', correct: true}, {text: 'False', correct: false}];
    if (type === 'short_answer') q.answers = [''];
    if (type === 'matching') q.pairs = [{item: '', match: ''}, {item: '', match: ''}];
    return q;
}

const text = (value, onInput, label, {area = false, placeholder = ''} = {}) => area
    ? html`<textarea class="form-control form-control-sm" rows="2" aria-label=${label} placeholder=${placeholder}
                     .value=${value || ''} @input=${e => onInput(e.target.value)}></textarea>`
    : html`<input class="form-control form-control-sm" aria-label=${label} placeholder=${placeholder}
                  .value=${value || ''} @input=${e => onInput(e.target.value)}>`;

const iconButton = (icon, label, onClick, disabled = false) => html`
    <button type="button" class="btn btn-sm artifact-icon" aria-label=${label} title=${label} ?disabled=${disabled}
            @click=${onClick}><span class="material-symbols-outlined" aria-hidden="true">${icon}</span></button>`;

/** Editable view of a course outline; [update] is called after every change. */
export function outlineForm(o, update) {
    o.sections = o.sections || [];
    return html`
        <div class="artifact-form">
            <label class="form-label">Course title</label>
            ${text(o.title, v => { o.title = v; update(false); }, 'Course title')}
            ${o.sections.map((s, si) => html`
                <fieldset class="artifact-card">
                    <div class="artifact-card-head">
                        <strong>Section ${si + 1}</strong>
                        <span class="flex-grow-1"></span>
                        ${iconButton('arrow_upward', 'Move section up', () => { move(o.sections, si, -1); update(); }, si === 0)}
                        ${iconButton('arrow_downward', 'Move section down', () => { move(o.sections, si, 1); update(); }, si === o.sections.length - 1)}
                        ${iconButton('delete', 'Delete section', () => { o.sections.splice(si, 1); update(); })}
                    </div>
                    ${text(s.title, v => { s.title = v; update(false); }, 'Section title', {placeholder: 'Section title'})}
                    ${(s.activities = s.activities || []).map((a, ai) => html`
                        <div class="artifact-row">
                            <div class="artifact-row-main">
                                ${text(a.title, v => { a.title = v; update(false); }, 'Activity title', {placeholder: 'Activity'})}
                                <div class="artifact-inline">
                                    <select class="form-select form-select-sm" aria-label="Activity type"
                                            @change=${e => { a.type = e.target.value; update(false); }}>
                                        ${ACTIVITY_TYPES.map(t => html`<option value=${t} ?selected=${a.type === t}>${t}</option>`)}
                                    </select>
                                    <input class="form-control form-control-sm artifact-minutes" type="number" min="0" max="600"
                                           aria-label="Minutes" placeholder="min" .value=${a.minutes ?? ''}
                                           @input=${e => { a.minutes = e.target.value === '' ? undefined : Number(e.target.value); update(false); }}>
                                </div>
                                ${text(a.summary, v => { a.summary = v; update(false); }, 'Summary', {area: true, placeholder: 'What happens'})}
                            </div>
                            ${iconButton('close', 'Remove activity', () => { s.activities.splice(ai, 1); update(); })}
                        </div>`)}
                    <button type="button" class="btn btn-sm btn-outline-secondary"
                            @click=${() => { s.activities.push({title: '', type: 'lecture', summary: ''}); update(); }}>Add activity</button>
                </fieldset>`)}
            <button type="button" class="btn btn-sm btn-outline-primary"
                    @click=${() => { o.sections.push({title: '', activities: []}); update(); }}>Add section</button>
        </div>`;
}

/** Editable view of a quiz. */
export function quizForm(q, update) {
    q.questions = q.questions || [];
    return html`
        <div class="artifact-form">
            <label class="form-label">Quiz title</label>
            ${text(q.title, v => { q.title = v; update(false); }, 'Quiz title')}
            ${q.questions.map((item, qi) => html`
                <fieldset class="artifact-card">
                    <div class="artifact-card-head">
                        <strong>Question ${qi + 1}</strong>
                        <select class="form-select form-select-sm artifact-type" aria-label="Question type"
                                @change=${e => { q.questions[qi] = {...blankQuestion(e.target.value), text: item.text, feedback: item.feedback}; update(); }}>
                            ${Object.entries(QUESTION_TYPES).map(([k, label]) => html`<option value=${k} ?selected=${item.type === k}>${label}</option>`)}
                        </select>
                        <span class="flex-grow-1"></span>
                        ${iconButton('arrow_upward', 'Move question up', () => { move(q.questions, qi, -1); update(); }, qi === 0)}
                        ${iconButton('arrow_downward', 'Move question down', () => { move(q.questions, qi, 1); update(); }, qi === q.questions.length - 1)}
                        ${iconButton('delete', 'Delete question', () => { q.questions.splice(qi, 1); update(); })}
                    </div>
                    ${text(item.text, v => { item.text = v; update(false); }, 'Question text', {area: true, placeholder: 'Question'})}
                    ${answerFields(item, update)}
                    ${text(item.feedback, v => { item.feedback = v; update(false); }, 'Feedback', {placeholder: 'Feedback shown after answering (optional)'})}
                </fieldset>`)}
            <button type="button" class="btn btn-sm btn-outline-primary"
                    @click=${() => { q.questions.push(blankQuestion('multiple_choice')); update(); }}>Add question</button>
        </div>`;
}

function answerFields(item, update) {
    if (item.type === 'true_false') {
        const isTrue = (item.options || []).find(o => o.text === 'True')?.correct;
        return html`<div class="artifact-inline">
            <span>Correct answer:</span>
            ${['True', 'False'].map(label => html`
                <label class="form-check-label"><input class="form-check-input" type="radio"
                    ?checked=${(label === 'True') === !!isTrue}
                    @change=${() => { item.options = [{text: 'True', correct: label === 'True'}, {text: 'False', correct: label === 'False'}]; update(false); }}>
                    ${label}</label>`)}
        </div>`;
    }
    if (item.type === 'short_answer') {
        item.answers = item.answers || [''];
        return html`<div>
            ${item.answers.map((a, i) => html`<div class="artifact-row">
                ${text(a, v => { item.answers[i] = v; update(false); }, 'Accepted answer', {placeholder: 'Accepted answer'})}
                ${iconButton('close', 'Remove answer', () => { item.answers.splice(i, 1); update(); }, item.answers.length <= 1)}
            </div>`)}
            <button type="button" class="btn btn-sm btn-link" @click=${() => { item.answers.push(''); update(); }}>Add accepted answer</button>
        </div>`;
    }
    if (item.type === 'matching') {
        item.pairs = item.pairs || [];
        return html`<div>
            ${item.pairs.map((p, i) => html`<div class="artifact-row">
                ${text(p.item, v => { p.item = v; update(false); }, 'Item', {placeholder: 'Item'})}
                <span aria-hidden="true">→</span>
                ${text(p.match, v => { p.match = v; update(false); }, 'Match', {placeholder: 'Match'})}
                ${iconButton('close', 'Remove pair', () => { item.pairs.splice(i, 1); update(); }, item.pairs.length <= 2)}
            </div>`)}
            <button type="button" class="btn btn-sm btn-link" @click=${() => { item.pairs.push({item: '', match: ''}); update(); }}>Add pair</button>
        </div>`;
    }
    item.options = item.options || [];
    return html`<div>
        ${item.options.map((o, i) => html`<div class="artifact-row">
            <input class="form-check-input" type="checkbox" aria-label="Correct answer" ?checked=${o.correct}
                   @change=${e => { o.correct = e.target.checked; update(false); }}>
            ${text(o.text, v => { o.text = v; update(false); }, 'Option', {placeholder: 'Option'})}
            ${iconButton('close', 'Remove option', () => { item.options.splice(i, 1); update(); }, item.options.length <= 2)}
        </div>`)}
        <button type="button" class="btn btn-sm btn-link" @click=${() => { item.options.push({text: '', correct: false}); update(); }}>Add option</button>
    </div>`;
}
