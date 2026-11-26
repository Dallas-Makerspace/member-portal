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

registerType('course', {
    label: 'Online courses',
    order: 30,
    icon: 'school',
    tip: 'Online courses on DMS Learn',
    example: 'laser',
    since: '2026-09-01',
    gated: true,
});
