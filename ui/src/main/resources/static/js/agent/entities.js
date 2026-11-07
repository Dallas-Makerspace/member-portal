// Turns links to members and groups into their components. Runs on sanitized DOM, so the sanitizer
// never has to allow custom elements. Add a row here for a new kind of pill.
const MAX_NAME = 80;

const ENTITIES = [
    {
        path: /^\/profile\/@([A-Za-z0-9_.-]{1,64})\/?$/,
        build(doc, match, link) {
            const card = doc.createElement('dms-member-card');
            card.setAttribute('username', match[1]);
            card.setAttribute('state', link.closest('td, th') ? 'mini' : 'inline');
            return card;
        },
    },
    {
        path: /^\/groups\/([^/?#\s]+)\/?$/,
        build(doc, match, link) {
            const pill = doc.createElement('dms-group-pill');
            pill.setAttribute('name', link.textContent.trim().slice(0, MAX_NAME));
            pill.setAttribute('href', `/groups/${match[1]}`);
            return pill;
        },
    },
];

/** Replaces each matching link under root; any other link stays as it is. */
export function upgradeEntities(root) {
    for (const link of root.querySelectorAll('a[href]')) {
        const href = link.getAttribute('href');
        for (const entity of ENTITIES) {
            const match = entity.path.exec(href);
            if (match) {
                link.replaceWith(entity.build(link.ownerDocument, match, link));
                break;
            }
        }
    }
}
