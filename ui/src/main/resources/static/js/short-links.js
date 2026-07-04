/**
 * Short Links Management UI
 * Handles namespace and short link CRUD operations via backend API
 */

(function() {
    'use strict';

    const config = window.shortLinksConfig || {};
    const API_BASE = '/backend-api/short-links';

    let namespaces = [];
    let shortLinks = [];
    let groups = [];
    let currentEditingLink = null;

    /**
     * Initialize the page
     */
    async function init() {
        // Load groups for dropdown
        await loadGroups();

        // Load namespaces
        await loadNamespaces();

        // Load short links
        await loadShortLinks();

        // Set up event listeners
        setupEventListeners();

        // Update UI based on permissions
        updateUIPermissions();
    }

    /**
     * Load groups from backend API
     */
    async function loadGroups() {
        try {
            const response = await fetch('/backend-api/groups');
            if (!response.ok) {
                console.error('Failed to load groups:', response.status);
                return;
            }
            const result = await response.json();
            groups = result.data || [];

            // Populate owner group dropdown
            populateGroupDropdown();
        } catch (error) {
            console.error('Failed to load groups:', error);
        }
    }

    /**
     * Populate owner group dropdown
     */
    function populateGroupDropdown() {
        const groupSelect = document.getElementById('namespaceOwnerGroup');
        if (!groupSelect) return;

        groupSelect.innerHTML = '<option value="">Select a group (optional)...</option>' +
            groups.map(group => `<option value="${group.id}">${escapeHtml(group.name)}</option>`).join('');
    }

    /**
     * Load namespaces from API
     */
    async function loadNamespaces() {
        try {
            const response = await fetch(`${API_BASE}/namespaces`);
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const result = await response.json();
            namespaces = result.data || [];

            // Render namespace list
            if (config.canManageNamespaces) {
                renderNamespacesList();
            }

            // Populate namespace filter and modal dropdowns
            populateNamespaceDropdowns();
        } catch (error) {
            console.error('Failed to load namespaces:', error);
            showError('Failed to load namespaces');
        }
    }

    /**
     * Load short links from API
     */
    async function loadShortLinks() {
        try {
            const response = await fetch(`${API_BASE}/links`);
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const result = await response.json();
            shortLinks = result.data || [];

            // Render short links list
            renderShortLinksList();
        } catch (error) {
            console.error('Failed to load short links:', error);
            showError('Failed to load short links');
        }
    }

    /**
     * Render namespaces list (Infrastructure only)
     */
    function renderNamespacesList() {
        const container = document.getElementById('namespacesList');
        if (!container) return;

        if (namespaces.length === 0) {
            container.innerHTML = '<p class="text-muted">No namespaces found. Create one to get started.</p>';
            return;
        }

        const html = `
            <div class="table-responsive">
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>Name</th>
                            <th>Aliases</th>
                            <th>Owner Type</th>
                            <th>Status</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${namespaces.map(ns => `
                            <tr>
                                <td>${escapeHtml(ns.name)}</td>
                                <td>${ns.aliases.map(a => `<span class="badge bg-secondary${a.isPrimary ? ' bg-primary' : ''}">${escapeHtml(a.alias)}</span>`).join(' ')}</td>
                                <td><span class="badge bg-info">${ns.ownerType}</span></td>
                                <td><span class="badge ${ns.isActive ? 'bg-success' : 'bg-secondary'}">${ns.isActive ? 'Active' : 'Inactive'}</span></td>
                                <td>
                                    <button class="btn btn-sm btn-outline-danger" onclick="window.shortLinksApp.deleteNamespace(${ns.id})">Delete</button>
                                </td>
                            </tr>
                        `).join('')}
                    </tbody>
                </table>
            </div>
        `;

        container.innerHTML = html;
    }

    /**
     * Render short links list with filters
     */
    function renderShortLinksList() {
        const container = document.getElementById('shortLinksList');
        if (!container) return;

        // Apply filters
        const namespaceFilter = document.getElementById('namespaceFilter')?.value || '';
        const statusFilter = document.getElementById('statusFilter')?.value || 'active';
        const searchText = (document.getElementById('searchBox')?.value || '').toLowerCase();

        let filtered = shortLinks.filter(link => {
            if (namespaceFilter && link.namespaceId != namespaceFilter) return false;
            if (statusFilter === 'active' && !link.isActive) return false;
            if (searchText && !link.slug.toLowerCase().includes(searchText) && !link.destinationUrl.toLowerCase().includes(searchText)) {
                return false;
            }
            return true;
        });

        if (filtered.length === 0) {
            container.innerHTML = '<p class="text-muted">No short links found. Create one to get started.</p>';
            return;
        }

        const html = `
            <div class="table-responsive">
                <table class="table table-hover">
                    <thead>
                        <tr>
                            <th>Short Link</th>
                            <th>Destination</th>
                            <th>Description</th>
                            <th>Clicks</th>
                            <th>Status</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${filtered.map(link => {
                            const namespace = namespaces.find(ns => ns.id === link.namespaceId);
                            const primaryAlias = namespace?.aliases.find(a => a.isPrimary)?.alias;
                            const path = namespace && primaryAlias ? `${primaryAlias}/${link.slug}` : link.slug;

                            return `
                            <tr>
                                <td>
                                    <strong>dallas.ms/${escapeHtml(path)}</strong><br/>
                                    ${namespace ? `<span class="badge bg-secondary">${escapeHtml(namespace.name)}</span>` : '<span class="badge bg-light text-dark">Root</span>'}
                                </td>
                                <td><a href="${escapeHtml(link.destinationUrl)}" target="_blank" class="text-truncate" style="max-width: 300px; display: inline-block;">${escapeHtml(link.destinationUrl)}</a></td>
                                <td>${escapeHtml(link.description || '')}</td>
                                <td>${link.clickCount || 0}</td>
                                <td><span class="badge ${link.isActive ? 'bg-success' : 'bg-secondary'}">${link.isActive ? 'Active' : 'Inactive'}</span></td>
                                <td>
                                    <button class="btn btn-sm btn-outline-primary" onclick="window.shortLinksApp.editLink(${link.id})">Edit</button>
                                    <button class="btn btn-sm btn-outline-danger" onclick="window.shortLinksApp.deleteLink(${link.id})">Delete</button>
                                </td>
                            </tr>
                            `;
                        }).join('')}
                    </tbody>
                </table>
            </div>
        `;

        container.innerHTML = html;
    }

    /**
     * Populate namespace dropdowns
     */
    function populateNamespaceDropdowns() {
        // Filter dropdown
        const filterSelect = document.getElementById('namespaceFilter');
        if (filterSelect) {
            filterSelect.innerHTML = '<option value="">All Namespaces</option>' +
                namespaces.map(ns => `<option value="${ns.id}">${escapeHtml(ns.name)}</option>`).join('');
        }

        // Create link modal dropdown
        const linkSelect = document.getElementById('linkNamespace');
        if (linkSelect) {
            linkSelect.innerHTML = '<option value="">Root Level (Auto-generated)</option>' +
                namespaces.map(ns => `<option value="${ns.id}">${escapeHtml(ns.name)}</option>`).join('');
        }
    }

    /**
     * Set up event listeners
     */
    function setupEventListeners() {
        // Namespace modal
        const saveNamespaceBtn = document.getElementById('saveNamespaceBtn');
        if (saveNamespaceBtn) {
            saveNamespaceBtn.addEventListener('click', handleCreateNamespace);
        }

        // Link modal
        const saveLinkBtn = document.getElementById('saveLinkBtn');
        if (saveLinkBtn) {
            saveLinkBtn.addEventListener('click', handleCreateOrUpdateLink);
        }

        // Namespace selector in link modal - show/hide slug field
        const linkNamespaceSelect = document.getElementById('linkNamespace');
        if (linkNamespaceSelect) {
            linkNamespaceSelect.addEventListener('change', handleNamespaceChange);
        }

        // Update preview as user types
        const linkSlug = document.getElementById('linkSlug');
        if (linkSlug) {
            linkSlug.addEventListener('input', updateLinkPreview);
        }

        // Filters
        const namespaceFilter = document.getElementById('namespaceFilter');
        const statusFilter = document.getElementById('statusFilter');
        const searchBox = document.getElementById('searchBox');

        if (namespaceFilter) {
            namespaceFilter.addEventListener('change', renderShortLinksList);
        }
        if (statusFilter) {
            statusFilter.addEventListener('change', renderShortLinksList);
        }
        if (searchBox) {
            searchBox.addEventListener('input', debounce(renderShortLinksList, 300));
        }

        // Reset form when modals are hidden
        const createNamespaceModal = document.getElementById('createNamespaceModal');
        const createLinkModal = document.getElementById('createLinkModal');

        if (createNamespaceModal) {
            createNamespaceModal.addEventListener('hidden.bs.modal', () => {
                document.getElementById('createNamespaceForm').reset();
            });
        }

        if (createLinkModal) {
            createLinkModal.addEventListener('hidden.bs.modal', () => {
                document.getElementById('createLinkForm').reset();
                currentEditingLink = null;
                document.getElementById('linkId').value = '';
                document.getElementById('createLinkModalLabel').textContent = 'Create Short Link';
                document.getElementById('saveLinkBtn').textContent = 'Create';

                // Re-enable slug field (it gets disabled during edit)
                const linkSlug = document.getElementById('linkSlug');
                if (linkSlug) {
                    linkSlug.disabled = false;
                }

                // Reset preview to show [auto-generated]
                updateLinkPreview();
            });
        }
    }

    /**
     * Handle namespace change in link modal
     */
    function handleNamespaceChange() {
        const namespaceSelect = document.getElementById('linkNamespace');
        const slugField = document.getElementById('slugField');
        const linkSlug = document.getElementById('linkSlug');

        if (namespaceSelect.value) {
            // Namespace selected - slug required
            slugField.style.display = 'block';
            linkSlug.required = true;
        } else {
            // Root level - slug optional (auto-generated)
            slugField.style.display = 'block';
            linkSlug.required = false;
        }

        updateLinkPreview();
    }

    /**
     * Update link preview
     */
    function updateLinkPreview() {
        const namespaceSelect = document.getElementById('linkNamespace');
        const linkSlug = document.getElementById('linkSlug');
        const previewPath = document.getElementById('previewPath');

        if (!previewPath) return;

        const namespace = namespaces.find(ns => ns.id == namespaceSelect.value);
        const slug = linkSlug.value || (namespace ? '...' : '[auto-generated]');

        if (namespace) {
            const primaryAlias = namespace.aliases.find(a => a.isPrimary)?.alias || namespace.aliases[0]?.alias;
            previewPath.textContent = `${primaryAlias}/${slug}`;
        } else {
            previewPath.textContent = slug;
        }
    }

    /**
     * Handle create namespace
     */
    async function handleCreateNamespace() {
        const name = document.getElementById('namespaceName').value;
        const ownerType = document.getElementById('namespaceOwnerType').value;
        const ownerGroupId = document.getElementById('namespaceOwnerGroup').value || null;
        const description = document.getElementById('namespaceDescription').value || null;
        const primaryAlias = document.getElementById('namespacePrimaryAlias').value;
        const additionalAliases = document.getElementById('namespaceAdditionalAliases').value
            .split(',')
            .map(a => a.trim())
            .filter(a => a.length > 0);

        try {
            const response = await fetch(`${API_BASE}/namespaces/create`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    name,
                    ownerType,
                    ownerGroupId: ownerGroupId ? parseInt(ownerGroupId) : null,
                    description,
                    primaryAlias,
                    additionalAliases
                })
            });

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.message || `HTTP ${response.status}`);
            }

            // Close modal
            const modal = bootstrap.Modal.getInstance(document.getElementById('createNamespaceModal'));
            modal.hide();

            // Reload namespaces
            await loadNamespaces();

            showSuccess('Namespace created successfully');
        } catch (error) {
            console.error('Failed to create namespace:', error);
            showError(error.message || 'Failed to create namespace');
        }
    }

    /**
     * Handle create or update link
     */
    async function handleCreateOrUpdateLink() {
        const linkId = document.getElementById('linkId').value;
        const namespaceId = document.getElementById('linkNamespace').value || null;
        const slug = document.getElementById('linkSlug').value || null;
        const destinationUrl = document.getElementById('linkDestination').value;
        const description = document.getElementById('linkDescription').value || null;

        const isEdit = !!linkId;

        try {
            let response;
            if (isEdit) {
                // Update
                response = await fetch(`${API_BASE}/links/${linkId}/update`, {
                    method: 'PATCH',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({
                        slug: currentEditingLink.slug, // Keep original slug
                        destinationUrl,
                        description,
                        isActive: currentEditingLink.isActive
                    })
                });
            } else {
                // Create
                response = await fetch(`${API_BASE}/links/create`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({
                        namespaceId: namespaceId ? parseInt(namespaceId) : null,
                        slug,
                        destinationUrl,
                        description
                    })
                });
            }

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.message || `HTTP ${response.status}`);
            }

            // Close modal
            const modal = bootstrap.Modal.getInstance(document.getElementById('createLinkModal'));
            modal.hide();

            // Reload links
            await loadShortLinks();

            showSuccess(isEdit ? 'Link updated successfully' : 'Link created successfully');
        } catch (error) {
            console.error('Failed to save link:', error);
            showError(error.message || 'Failed to save link');
        }
    }

    /**
     * Edit a short link
     */
    function editLink(linkId) {
        const link = shortLinks.find(l => l.id === linkId);
        if (!link) return;

        currentEditingLink = link;

        // Populate form
        document.getElementById('linkId').value = link.id;
        document.getElementById('linkNamespace').value = link.namespaceId || '';
        document.getElementById('linkSlug').value = link.slug;
        document.getElementById('linkSlug').disabled = true; // Can't change slug
        document.getElementById('linkDestination').value = link.destinationUrl;
        document.getElementById('linkDescription').value = link.description || '';

        // Update modal title
        document.getElementById('createLinkModalLabel').textContent = 'Edit Short Link';
        document.getElementById('saveLinkBtn').textContent = 'Update';

        // Update preview
        handleNamespaceChange();

        // Show modal
        const modal = new bootstrap.Modal(document.getElementById('createLinkModal'));
        modal.show();
    }

    /**
     * Delete a namespace
     */
    async function deleteNamespace(namespaceId) {
        if (!confirm('Are you sure you want to delete this namespace? This will also delete all associated links.')) {
            return;
        }

        try {
            const response = await fetch(`${API_BASE}/namespaces/${namespaceId}/delete`, {
                method: 'DELETE'
            });

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.message || `HTTP ${response.status}`);
            }

            await loadNamespaces();
            await loadShortLinks();

            showSuccess('Namespace deleted successfully');
        } catch (error) {
            console.error('Failed to delete namespace:', error);
            showError(error.message || 'Failed to delete namespace');
        }
    }

    /**
     * Delete a short link
     */
    async function deleteLink(linkId) {
        if (!confirm('Are you sure you want to delete this short link?')) {
            return;
        }

        try {
            const response = await fetch(`${API_BASE}/links/${linkId}/delete`, {
                method: 'DELETE'
            });

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.message || `HTTP ${response.status}`);
            }

            await loadShortLinks();

            showSuccess('Link deleted successfully');
        } catch (error) {
            console.error('Failed to delete link:', error);
            showError(error.message || 'Failed to delete link');
        }
    }

    /**
     * Update UI based on permissions
     */
    function updateUIPermissions() {
        // Hide namespace management if not infrastructure
        if (!config.canManageNamespaces) {
            const namespacesRow = document.querySelector('.row:has(#namespacesList)');
            if (namespacesRow) {
                namespacesRow.style.display = 'none';
            }
        }
    }

    /**
     * Show success message
     */
    function showSuccess(message) {
        showToast(message, 'success');
    }

    /**
     * Show error message
     */
    function showError(message) {
        showToast(message, 'danger');
    }

    /**
     * Show Bootstrap toast notification
     */
    function showToast(message, type = 'success') {
        const toastEl = document.getElementById('toastNotification');
        const toastMessage = document.getElementById('toastMessage');

        if (!toastEl || !toastMessage) return;

        // Set message
        toastMessage.textContent = message;

        // Set color based on type (success = green, danger = red)
        toastEl.className = 'toast align-items-center border-0 text-bg-' + type;

        // Show toast
        const toast = new bootstrap.Toast(toastEl, {
            autohide: true,
            delay: 3000
        });
        toast.show();
    }

    /**
     * Escape HTML to prevent XSS
     */
    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    /**
     * Debounce helper
     */
    function debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    }

    // Export public API
    window.shortLinksApp = {
        editLink,
        deleteLink,
        deleteNamespace
    };

    // Initialize on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
