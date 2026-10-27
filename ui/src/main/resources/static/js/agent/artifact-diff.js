/** Line diff of two texts: [{kind: 'same' | 'add' | 'del', text}], in reading order. */
export function lineDiff(before, after) {
    const a = before.split('\n');
    const b = after.split('\n');
    const n = a.length;
    const m = b.length;
    // Longest common subsequence table, filled from the end so the walk below goes forward.
    const lcs = Array.from({length: n + 1}, () => new Array(m + 1).fill(0));
    for (let i = n - 1; i >= 0; i--) {
        for (let j = m - 1; j >= 0; j--) {
            lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        }
    }
    const out = [];
    let i = 0;
    let j = 0;
    while (i < n && j < m) {
        if (a[i] === b[j]) {
            out.push({kind: 'same', text: a[i]});
            i++;
            j++;
        } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
            out.push({kind: 'del', text: a[i++]});
        } else {
            out.push({kind: 'add', text: b[j++]});
        }
    }
    while (i < n) out.push({kind: 'del', text: a[i++]});
    while (j < m) out.push({kind: 'add', text: b[j++]});
    return out;
}

/** JSON documents are compared pretty-printed so a small edit is a small diff. */
export function readable(kind, content) {
    if (kind === 'markdown') return content;
    try {
        return JSON.stringify(JSON.parse(content), null, 2);
    } catch (e) {
        return content;
    }
}

/** Keeps changed lines and [context] lines around them; other runs become one {kind: 'gap'} entry. */
export function withContext(diff, context = 3) {
    const keep = new Array(diff.length).fill(false);
    diff.forEach((line, i) => {
        if (line.kind === 'same') return;
        for (let j = Math.max(0, i - context); j <= Math.min(diff.length - 1, i + context); j++) keep[j] = true;
    });
    const out = [];
    diff.forEach((line, i) => {
        if (keep[i]) out.push(line);
        else if (out.length && out[out.length - 1].kind !== 'gap') out.push({kind: 'gap', text: '…'});
    });
    return out;
}
