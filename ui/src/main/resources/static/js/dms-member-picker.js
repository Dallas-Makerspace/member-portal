/**
 * DmsMemberPicker - A web component for searching and multi-selecting people, with removable
 * chips, then submitting the selected usernames to a caller-supplied endpoint. Not tied to any
 * particular use case (group membership, mailing lists, event rosters, ...) — the host page
 * configures the endpoint, button label, and placeholder text.
 *
 * @element dms-member-picker
 * @attribute {String} add-endpoint - Required. URL to POST { usernames: [...] } to.
 * @attribute {String} existing-usernames - JSON array string of usernames to show as already
 *   added (dimmed, non-selectable, with an "Added" badge) instead of as addable candidates.
 * @attribute {String} add-button-label - Optional. Text for the submit button. Default "Add".
 * @attribute {String} search-placeholder - Optional. Search input placeholder. Default
 *   "Search to add...".
 * @attribute {String} reload-on-success - Optional. Set to the literal string "false" to skip
 *   the default "reload the page shortly after a successful add" behavior. Default: reload.
 *
 * Features:
 * - Text input with autocomplete (debounced 300ms)
 * - Dropdown showing up to 8 matching members
 * - Multi-select with removable chips (Gmail-style)
 * - Keyboard navigation (arrow keys, Enter, Escape, Backspace)
 * - Submits selected usernames via POST to `add-endpoint`
 * - Success/error message display
 * - Dispatches `dms-picker-add-success` / `dms-picker-add-error` CustomEvents so a host page can
 *   hook additional behavior (e.g. updating in place instead of reloading) without this
 *   component needing to know what that behavior is
 * - Reloads the page on successful add unless `reload-on-success="false"`
 */

// Module-level cache for member data (fetched once across all picker instances)
let cachedMemberData = null;
let memberDataFetched = false;

const DEFAULT_ADD_BUTTON_LABEL = 'Add';
const DEFAULT_SEARCH_PLACEHOLDER = 'Search to add...';

class DmsMemberPicker extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
    this.selectedMembers = []; // Array of member objects {username, displayName, avatarUrl}
    this.allMembers = []; // All members from /search-preload
    this.filteredResults = []; // Filtered members for dropdown
    this.selectedIndex = -1; // Index in dropdown for keyboard nav
    this.addEndpoint = '';
    this.existingUsernames = [];
    this.addButtonLabel = DEFAULT_ADD_BUTTON_LABEL;
    this.searchPlaceholder = DEFAULT_SEARCH_PLACEHOLDER;
    this.reloadOnSuccess = true;
    this.isAddingMembers = false;
  }

  static get observedAttributes() {
    return [
      'add-endpoint',
      'existing-usernames',
      'add-button-label',
      'search-placeholder',
      'reload-on-success',
    ];
  }

  attributeChangedCallback(name, oldValue, newValue) {
    if (name === 'add-endpoint') {
      this.addEndpoint = newValue || '';
    } else if (name === 'existing-usernames') {
      try {
        this.existingUsernames = newValue ? JSON.parse(newValue) : [];
      } catch (e) {
        console.error('Failed to parse existing-usernames JSON:', e);
        this.existingUsernames = [];
      }
    } else if (name === 'add-button-label') {
      this.addButtonLabel = newValue || DEFAULT_ADD_BUTTON_LABEL;
    } else if (name === 'search-placeholder') {
      this.searchPlaceholder = newValue || DEFAULT_SEARCH_PLACEHOLDER;
    } else if (name === 'reload-on-success') {
      this.reloadOnSuccess = newValue !== 'false';
    }
  }

  connectedCallback() {
    this._outsideClickHandler = (e) => this.handleOutsideClick(e);
    if (!this.addEndpoint) {
      console.error('dms-member-picker: missing required "add-endpoint" attribute');
    }
    this.render();
    this.setupEventListeners();
    this.fetchMembers();
  }

  disconnectedCallback() {
    document.removeEventListener('click', this._outsideClickHandler);
  }

  render() {
    this.shadowRoot.innerHTML = `
      <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
      <style>
        :host {
          display: block;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        }

        .picker-container {
          position: relative;
          width: 100%;
          margin: 0;
        }

        .input-container {
          display: flex;
          flex-wrap: wrap;
          align-items: center;
          gap: 4px;
          padding: 6px 8px 6px 12px;
          border: 1px solid #dfe1e5;
          border-radius: 8px;
          background-color: white;
          min-height: 44px;
          box-shadow: 0 1px 4px rgba(32, 33, 36, 0.2);
          transition: border-color 0.2s ease;
        }

        .input-container:focus-within {
          border-color: #4a90e2;
          box-shadow: 0 1px 6px rgba(74, 144, 226, 0.3);
        }

        .input-container.has-results {
          border-radius: 8px 8px 0 0;
          border-bottom: none;
        }

        .chip {
          display: inline-flex;
          align-items: center;
          gap: 4px;
          background-color: #6c757d;
          color: white;
          padding: 2px 8px;
          border-radius: 12px;
          font-size: 13px;
          white-space: nowrap;
          flex-shrink: 0;
        }

        .chip-remove {
          background: none;
          border: none;
          color: white;
          cursor: pointer;
          font-size: 16px;
          padding: 0;
          display: flex;
          align-items: center;
          justify-content: center;
          line-height: 1;
          width: 16px;
          height: 16px;
          margin-left: 2px;
        }

        .chip-remove:hover {
          opacity: 0.8;
        }

        .search-input {
          flex-grow: 1;
          min-width: 120px;
          border: none;
          outline: none;
          font-size: 14px;
          padding: 4px 0;
          background-color: transparent;
          font-family: inherit;
        }

        .search-input::placeholder {
          color: #5f6368;
        }

        .dropdown-container {
          position: absolute;
          top: 100%;
          left: 0;
          right: 0;
          width: 100%;
          box-sizing: border-box;
          border: 1px solid #dfe1e5;
          border-top: none;
          border-radius: 0 0 8px 8px;
          box-shadow: 0 4px 6px rgba(32, 33, 36, 0.28);
          background-color: white;
          display: none;
          z-index: 1000;
          max-height: 320px;
          overflow-y: auto;
        }

        .dropdown-container.visible {
          display: block;
        }

        .dropdown-list {
          list-style: none;
          padding: 0;
          margin: 0;
        }

        .dropdown-list li {
          padding: 8px 15px;
          cursor: pointer;
          border-bottom: 1px solid #f0f0f0;
          transition: background-color 0.15s ease;
        }

        .dropdown-list li:last-child {
          border-bottom: none;
          border-radius: 0 0 8px 8px;
        }

        .dropdown-list li:hover,
        .dropdown-list li.selected {
          background-color: #f0f0f0;
        }

        .dropdown-list dms-member-card {
          pointer-events: none;
        }

        .dropdown-list li.disabled-member {
          opacity: 0.55;
          cursor: default;
        }

        .disabled-badge {
          font-size: 10px;
          font-weight: 600;
          color: #fff;
          background-color: #dc3545;
          border-radius: 3px;
          padding: 1px 5px;
          flex-shrink: 0;
          text-transform: uppercase;
          letter-spacing: 0.3px;
        }

        .added-badge {
          font-size: 10px;
          font-weight: 600;
          color: #fff;
          background-color: #6c757d;
          border-radius: 3px;
          padding: 1px 5px;
          flex-shrink: 0;
          text-transform: uppercase;
          letter-spacing: 0.3px;
        }

        .loading-text {
          padding: 12px 15px;
          font-style: italic;
          color: #5f6368;
          text-align: center;
        }

        .no-results-text {
          padding: 12px 15px;
          text-align: center;
          color: #5f6368;
          font-size: 13px;
        }

        .add-button {
          flex-shrink: 0;
          padding: 5px 14px;
          font-size: 13px;
          margin-left: 4px;
          white-space: nowrap;
        }

        .add-button:disabled {
          opacity: 0.6;
          cursor: not-allowed;
        }

        .status-message {
          font-size: 13px;
          margin-top: 6px;
          line-height: 1.4;
        }

        .success-message {
          color: #28a745;
        }

        .error-message {
          color: #dc3545;
        }

        .hint-message {
          color: #5f6368;
        }
      </style>

      <div class="picker-container">
        <div class="input-container">
          <input
            type="text"
            class="search-input"
            placeholder="${this.searchPlaceholder}"
            autocomplete="off"
          />
          <button class="add-button btn btn-primary btn-sm" disabled>${this.addButtonLabel}</button>
          <div class="dropdown-container">
            <ul class="dropdown-list"></ul>
          </div>
        </div>
        <div class="status-message"></div>
      </div>
    `;
  }

  setupEventListeners() {
    const input = this.shadowRoot.querySelector('.search-input');
    const addButton = this.shadowRoot.querySelector('.add-button');

    input.addEventListener('focus', () => this.handleInputFocus());
    input.addEventListener('input', this.debounce(() => this.handleInputChange(), 300));
    input.addEventListener('keydown', (e) => this.handleKeydown(e));
    addButton.addEventListener('click', () => this.handleAddMembers());

    // Close dropdown on outside click (stored as named handler so disconnectedCallback can remove it)
    document.addEventListener('click', this._outsideClickHandler);
  }

  async fetchMembers() {
    // Return cached data if already fetched
    if (memberDataFetched) {
      this.allMembers = cachedMemberData || [];
      return;
    }

    try {
      const response = await fetch('/search-preload');
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      const json = await response.json();
      const data = json['data'] || [];
      this.allMembers = data.filter(item => item.type === 'member');
      cachedMemberData = this.allMembers;
      memberDataFetched = true;
    } catch (error) {
      console.error('Error fetching member data:', error);
      this.allMembers = [];
    }
  }

  handleInputFocus() {
    // Optionally open dropdown on focus with existing query
    const input = this.shadowRoot.querySelector('.search-input');
    if (input.value.trim()) {
      this.filterAndDisplayResults();
    }
  }

  handleInputChange() {
    const statusMessage = this.shadowRoot.querySelector('.status-message');
    if (statusMessage.textContent) {
      statusMessage.className = 'status-message';
      statusMessage.textContent = '';
    }
    this.filterAndDisplayResults();
    this.updateAddButtonState();
  }

  filterAndDisplayResults() {
    const input = this.shadowRoot.querySelector('.search-input');
    const query = input.value.toLowerCase().trim();

    if (!query) {
      this.hideDropdown();
      return;
    }

    // Get usernames of already selected members
    const selectedUsernames = new Set(this.selectedMembers.map(m => m.username));

    // Match against username, display name (include disabled/already-added members so they can
    // be shown with a visual indicator rather than silently disappearing from results)
    const matches = this.allMembers.filter(member => {
      if (selectedUsernames.has(member.username)) return false;
      const displayName = (member.displayName || '').toLowerCase();
      const username = (member.username || '').toLowerCase();
      return displayName.includes(query) || username.includes(query);
    });

    // Addable members first, already-added members after — so a broad query never crowds out
    // people you can actually add behind a wall of "Added" chips.
    const existingSet = new Set(this.existingUsernames);
    const addable = matches.filter(m => !existingSet.has(m.username));
    const alreadyAdded = matches
        .filter(m => existingSet.has(m.username))
        // Clone rather than mutate — these objects are shared via the module-level member cache.
        .map(m => ({ ...m, alreadyAdded: true }));
    this.filteredResults = [...addable, ...alreadyAdded].slice(0, 8); // Limit to 8 results

    this.selectedIndex = -1;
    this.displayDropdownResults();
  }

  displayDropdownResults() {
    const dropdownContainer = this.shadowRoot.querySelector('.dropdown-container');
    const dropdownList = this.shadowRoot.querySelector('.dropdown-list');
    const inputContainer = this.shadowRoot.querySelector('.input-container');

    dropdownList.innerHTML = '';

    if (this.filteredResults.length === 0) {
      const li = document.createElement('li');
      li.className = 'no-results-text';
      li.textContent = 'No members found';
      dropdownList.appendChild(li);
      dropdownContainer.classList.add('visible');
      inputContainer.classList.add('has-results');
      return;
    }

    this.filteredResults.forEach((member, index) => {
      const li = document.createElement('li');
      li.dataset.index = index;
      if (!member.enabled || member.alreadyAdded) {
        li.classList.add('disabled-member');
      }

      // Create a mini member card-like display
      const memberDiv = document.createElement('div');
      memberDiv.style.display = 'flex';
      memberDiv.style.alignItems = 'center';
      memberDiv.style.gap = '10px';

      // Avatar
      const avatarDiv = document.createElement('div');
      avatarDiv.style.width = '32px';
      avatarDiv.style.height = '32px';
      avatarDiv.style.borderRadius = '50%';
      avatarDiv.style.border = '1px solid #ccc';
      avatarDiv.style.display = 'flex';
      avatarDiv.style.alignItems = 'center';
      avatarDiv.style.justifyContent = 'center';
      avatarDiv.style.flexShrink = '0';
      avatarDiv.style.fontSize = '12px';
      avatarDiv.style.fontWeight = '600';
      avatarDiv.style.color = '#333';
      avatarDiv.style.backgroundColor = '#f0f0f0';
      avatarDiv.style.overflow = 'hidden';

      if (member.avatarUrl) {
        const img = document.createElement('img');
        img.src = member.avatarUrl;
        img.style.width = '100%';
        img.style.height = '100%';
        img.style.objectFit = 'cover';
        avatarDiv.appendChild(img);
      } else {
        const letter = (member.displayName || member.username).charAt(0).toUpperCase();
        avatarDiv.textContent = letter;
      }

      memberDiv.appendChild(avatarDiv);

      // Member info
      const infoDiv = document.createElement('div');
      infoDiv.style.flex = '1';
      infoDiv.style.minWidth = '0';

      const usernameDiv = document.createElement('div');
      usernameDiv.style.fontSize = '13px';
      usernameDiv.style.fontWeight = '500';
      usernameDiv.style.color = '#4a90e2';
      usernameDiv.style.overflow = 'hidden';
      usernameDiv.style.textOverflow = 'ellipsis';
      usernameDiv.style.whiteSpace = 'nowrap';
      usernameDiv.textContent = `@${member.username}`;

      const displayNameDiv = document.createElement('div');
      displayNameDiv.style.fontSize = '12px';
      displayNameDiv.style.color = '#666';
      displayNameDiv.style.overflow = 'hidden';
      displayNameDiv.style.textOverflow = 'ellipsis';
      displayNameDiv.style.whiteSpace = 'nowrap';
      displayNameDiv.textContent = member.displayName || member.username;

      infoDiv.appendChild(usernameDiv);
      infoDiv.appendChild(displayNameDiv);
      memberDiv.appendChild(infoDiv);

      if (!member.enabled) {
        const badge = document.createElement('span');
        badge.className = 'disabled-badge';
        badge.textContent = 'Disabled';
        memberDiv.appendChild(badge);
      } else if (member.alreadyAdded) {
        const badge = document.createElement('span');
        badge.className = 'added-badge';
        badge.textContent = 'Added';
        memberDiv.appendChild(badge);
      }

      li.appendChild(memberDiv);
      li.addEventListener('click', () => this.selectMember(member));
      dropdownList.appendChild(li);
    });

    dropdownContainer.classList.add('visible');
    inputContainer.classList.add('has-results');
  }

  hideDropdown() {
    const dropdownContainer = this.shadowRoot.querySelector('.dropdown-container');
    const inputContainer = this.shadowRoot.querySelector('.input-container');
    dropdownContainer.classList.remove('visible');
    inputContainer.classList.remove('has-results');
    this.selectedIndex = -1;
  }

  handleKeydown(e) {
    const dropdownContainer = this.shadowRoot.querySelector('.dropdown-container');
    const isDropdownVisible = dropdownContainer.classList.contains('visible');

    switch (e.key) {
      case 'ArrowDown':
        if (!isDropdownVisible || this.filteredResults.length === 0) return;
        e.preventDefault();
        this.selectedIndex = Math.min(this.selectedIndex + 1, this.filteredResults.length - 1);
        this.updateSelectedDropdownItem();
        break;

      case 'ArrowUp':
        if (!isDropdownVisible || this.filteredResults.length === 0) return;
        e.preventDefault();
        this.selectedIndex = Math.max(this.selectedIndex - 1, -1);
        this.updateSelectedDropdownItem();
        break;

      case 'Enter':
        if (!isDropdownVisible || this.selectedIndex < 0 || this.selectedIndex >= this.filteredResults.length) {
          return;
        }
        e.preventDefault();
        this.selectMember(this.filteredResults[this.selectedIndex]);
        break;

      case 'Escape':
        e.preventDefault();
        this.hideDropdown();
        break;

      case 'Backspace':
        const input = this.shadowRoot.querySelector('.search-input');
        // If input is empty, remove last chip
        if (!input.value.trim() && this.selectedMembers.length > 0) {
          e.preventDefault();
          this.selectedMembers.pop();
          this.updateSelectedChips();
          this.filterAndDisplayResults();
        }
        break;
    }
  }

  updateSelectedDropdownItem() {
    const items = this.shadowRoot.querySelectorAll('.dropdown-list li');
    items.forEach((item, index) => {
      if (index === this.selectedIndex) {
        item.classList.add('selected');
        item.scrollIntoView({ block: 'nearest' });
      } else {
        item.classList.remove('selected');
      }
    });
  }

  selectMember(member) {
    if (!member.enabled) {
      const input = this.shadowRoot.querySelector('.search-input');
      input.value = '';
      const statusMessage = this.shadowRoot.querySelector('.status-message');
      statusMessage.className = 'status-message error-message';
      statusMessage.textContent = `Cannot add @${member.username}: this account is disabled. Please choose an active member.`;
      this.hideDropdown();
      this.updateAddButtonState();
      return;
    }
    if (this.existingUsernames.includes(member.username)) {
      const input = this.shadowRoot.querySelector('.search-input');
      input.value = '';
      const statusMessage = this.shadowRoot.querySelector('.status-message');
      statusMessage.className = 'status-message error-message';
      statusMessage.textContent = `@${member.username} has already been added.`;
      this.hideDropdown();
      this.updateAddButtonState();
      return;
    }
    this.selectedMembers.push(member);
    const input = this.shadowRoot.querySelector('.search-input');
    input.value = '';
    this.updateSelectedChips();
    this.hideDropdown();
    input.focus();
  }

  updateSelectedChips() {
    const inputContainer = this.shadowRoot.querySelector('.input-container');
    const input = this.shadowRoot.querySelector('.search-input');

    // Remove existing chips
    inputContainer.querySelectorAll('.chip').forEach(chip => chip.remove());

    // Add chips for selected members
    this.selectedMembers.forEach((member, index) => {
      const chip = document.createElement('div');
      chip.className = 'chip';

      const label = document.createElement('span');
      label.textContent = `@${member.username}`;

      const removeBtn = document.createElement('button');
      removeBtn.className = 'chip-remove';
      removeBtn.innerHTML = '×';
      removeBtn.setAttribute('aria-label', `Remove ${member.username}`);
      removeBtn.addEventListener('click', (e) => {
        e.preventDefault();
        this.selectedMembers.splice(index, 1);
        this.updateSelectedChips();
        this.filterAndDisplayResults();
        input.focus();
      });

      chip.appendChild(label);
      chip.appendChild(removeBtn);
      inputContainer.insertBefore(chip, input);
    });

    this.updateAddButtonState();
  }

  updateAddButtonState() {
    const addButton = this.shadowRoot.querySelector('.add-button');
    const input = this.shadowRoot.querySelector('.search-input');
    const statusMessage = this.shadowRoot.querySelector('.status-message');
    const hasUnresolvedText = input.value.trim().length > 0;

    addButton.disabled = this.selectedMembers.length === 0 || hasUnresolvedText;

    if (hasUnresolvedText && this.selectedMembers.length > 0 && !statusMessage.classList.contains('error-message')) {
      statusMessage.className = 'status-message hint-message';
      statusMessage.textContent = 'Pick a member from the dropdown to add them, or clear the search field.';
    } else if (!hasUnresolvedText && statusMessage.classList.contains('hint-message')) {
      statusMessage.className = 'status-message';
      statusMessage.textContent = '';
    }
  }

  async handleAddMembers() {
    if (this.selectedMembers.length === 0 || this.isAddingMembers) return;
    if (!this.addEndpoint) {
      console.error('dms-member-picker: cannot submit, "add-endpoint" attribute is not set');
      return;
    }

    this.isAddingMembers = true;
    const addButton = this.shadowRoot.querySelector('.add-button');
    const statusMessage = this.shadowRoot.querySelector('.status-message');

    addButton.disabled = true;
    statusMessage.className = 'status-message';
    statusMessage.textContent = '';

    const usernames = this.selectedMembers.map(m => m.username);

    try {
      const response = await fetch(this.addEndpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ usernames }),
      });

      if (response.ok) {
        statusMessage.className = 'status-message success-message';
        statusMessage.textContent = 'Members added successfully!';
        this.dispatchEvent(new CustomEvent('dms-picker-add-success', {
          detail: { usernames },
          bubbles: true,
          composed: true,
        }));
        if (this.reloadOnSuccess) {
          // Reload page after 800ms
          setTimeout(() => {
            window.location.reload();
          }, 800);
        }
      } else {
        let errorText = 'Failed to add members';
        try {
          const errorData = await response.json();
          errorText = errorData.message || errorData.error || errorText;
        } catch {}
        statusMessage.className = 'status-message error-message';
        statusMessage.textContent = errorText;
        addButton.disabled = this.selectedMembers.length === 0;
        this.dispatchEvent(new CustomEvent('dms-picker-add-error', {
          detail: { error: errorText },
          bubbles: true,
          composed: true,
        }));
      }
    } catch (error) {
      console.error('Error adding members:', error);
      statusMessage.className = 'status-message error-message';
      statusMessage.textContent = 'Error: ' + error.message;
      addButton.disabled = this.selectedMembers.length === 0;
      this.dispatchEvent(new CustomEvent('dms-picker-add-error', {
        detail: { error: error.message },
        bubbles: true,
        composed: true,
      }));
    } finally {
      this.isAddingMembers = false;
    }
  }

  handleOutsideClick(e) {
    const pickerContainer = this.shadowRoot.querySelector('.picker-container');
    if (pickerContainer && !pickerContainer.contains(e.target)) {
      this.hideDropdown();
    }
  }

  debounce(func, wait) {
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
}

customElements.define('dms-member-picker', DmsMemberPicker);
