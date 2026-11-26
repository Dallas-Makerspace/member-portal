// Display info per result type. Unregistered types still render with a generic row.
const types = new Map();

export function registerType(type, info) {
    types.set(type, { type, order: 100, icon: 'search', ...info });
}

export function typeInfo(type) {
    return types.get(type) || {
        type,
        label: humanize(type),
        order: 1000,
        icon: 'search',
    };
}

export function registeredTypes() {
    return [...types.values()].sort((a, b) => a.order - b.order);
}

function humanize(type) {
    const words = String(type || 'other').replace(/[-_]+/g, ' ').trim();
    const label = words.charAt(0).toUpperCase() + words.slice(1);
    return label.endsWith('s') ? label : `${label}s`;
}

registerType('member', {
    label: 'Members',
    order: 10,
    icon: 'person',
    tip: 'Members by name or username',
    since: '2024-01-01',
});

registerType('group', {
    label: 'Groups',
    order: 20,
    icon: 'groups',
    tip: 'Groups and committees',
    example: 'shop',
    since: '2024-01-01',
});

registerType('committee', {
    label: 'Committees',
    order: 15,
    icon: 'committee',
    tip: 'Committees and who chairs them',
    example: 'wood',
    since: '2026-11-27',
});

registerType('link', {
    label: 'Links',
    order: 25,
    icon: 'link',
    tip: 'Useful links, like Talk or the calendar',
    example: 'talk',
    since: '2026-11-27',
});

registerType('event', {
    label: 'Upcoming classes',
    order: 40,
    icon: 'calendar',
    tip: 'Upcoming classes and events',
    example: 'lathe',
    since: '2026-11-27',
});

registerType('course', {
    label: 'Online courses',
    order: 30,
    icon: 'school',
    tip: 'Online courses on DMS Learn',
    example: 'laser',
    since: '2026-09-01',
    gated: true,
});
