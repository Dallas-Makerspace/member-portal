import './dms-member-card.js';
import './dms-group-pill.js';

class DMSSearch extends HTMLElement {
    constructor() {
        super();
        this.attachShadow({ mode: 'open' });
        this.members = [];
        this.maxResults = 10; // Default value
        this.dataFetched = false;
        this.isRequestInFlight = false;
        this.abortController = null;
        this.spinnerTimeout = null;
        this.hadError = false;
        this.selectedIndex = -1;
        this.currentResults = [];
        this.pendingSearchQuery = null;
    }

    static get observedAttributes() {
        return ['max-results'];
    }

    attributeChangedCallback(name, oldValue, newValue) {
        if (name === 'max-results') {
            this.maxResults = parseInt(newValue, 10);
        }
    }

    connectedCallback() {
        this.render();
        this.setupEventListeners();
    }

    render() {
        this.shadowRoot.innerHTML = `
        <style>
        :host {
          --dms-search-bg-color: white;
          --dms-search-text-color: black;
          --dms-search-highlight-color: #f0f0f0;
        }
        .dms-search-container {
          font-family: Arial, sans-serif;
          max-width: 600px;
          min-width: 300px;
          position: relative;
        }
        .search-input-container {
          display: flex;
          align-items: center;
          border: 1px solid #dfe1e5;
          border-radius: 18px;
          padding: 2px 12px;
          box-shadow: 0 1px 4px rgba(32,33,36,0.2);
          background-color: var(--dms-search-bg-color);
          transition: border-radius 0.3s ease;
        }
        .search-input-container.results-visible {
          border-radius: 18px 18px 0 0;
          border-bottom: none;
        }
        input {
          flex-grow: 1;
          border: none;
          outline: none;
          font-size: 14px;
          padding: 6px 0;
          background-color: transparent;
        }
        .spinner {
          width: 16px;
          height: 16px;
          border: 2px solid #f3f3f3;
          border-top: 2px solid #5f6368;
          border-radius: 50%;
          animation: spin 1s linear infinite;
          margin-right: 8px;
          display: none;
        }
        @keyframes spin {
          0% { transform: rotate(0deg); }
          100% { transform: rotate(360deg); }
        }
        .clear-button {
          background: none;
          border: none;
          cursor: pointer;
          font-size: 18px;
          color: #5f6368;
          display: none;
        }
        .error-message {
          color: #d93025;
          font-size: 14px;
          padding: 8px 15px;
          display: none;
          border-top: 1px solid #fce8e6;
          background-color: #fef7f0;
        }
        .results-container {
          position: absolute;
          top: 100%;
          left: 0;
          right: 0;
          width: 100%;
          box-sizing: border-box;
          border: 1px solid #dfe1e5;
          border-top: none;
          border-radius: 0 0 18px 18px;
          box-shadow: 0 4px 6px rgba(32,33,36,0.28);
          background-color: var(--dms-search-bg-color);
          display: none;
          z-index: 1000;
        }
        .results-list {
          list-style-type: none;
          padding: 0;
          margin: 0;
          max-height: 300px;
          overflow-y: auto;
        }
        .results-list li {
          padding: 8px 15px;
          cursor: pointer;
        }
        .results-list li:hover,
        .results-list li.selected {
          background-color: var(--dms-search-highlight-color);
        }
        .results-list li:last-child {
          border-radius: 0 0 18px 18px;
        }
        .results-list li a,
        .results-list dms-member-card,
        .results-list dms-group-pill {
          pointer-events: none;
        }
        .results-list .course-name {
          font-weight: 600;
        }
        .results-list .course-label {
          margin-left: 8px;
          font-size: 0.75rem;
          opacity: 0.7;
        }
        .loading-indicator {
          font-style: italic;
          text-align: center;
          padding: 10px;
        }
      </style>
      <div class="dms-search-container">
      <div class="search-input-container">
        <div class="spinner"></div>
        <input type="text" placeholder="Search (Ctrl+K) members + groups">
        <button class="clear-button">✕</button>
      </div>
      <div class="error-message"></div>
      <div class="results-container">
        <div class="loading-indicator" style="display: none;">Loading...</div>
        <ul class="results-list"></ul>
      </div>
    </div>
    `;
    }

    setupEventListeners() {
        const input = this.shadowRoot.querySelector('input');
        const clearButton = this.shadowRoot.querySelector('.clear-button');

        input.addEventListener('focus', () => this.handleFocus());
        input.addEventListener('input', this.debounce(() => this.handleSearch(), 300));
        clearButton.addEventListener('click', () => this.clearSearch());
        input.addEventListener('input', () => this.toggleClearButton());
        input.addEventListener('keydown', (e) => this.handleKeydown(e));
        document.addEventListener('click', (e) => this.handleOutsideClick(e));
    }

    handleKeydown(event) {
        const resultsContainer = this.shadowRoot.querySelector('.results-container');
        const isResultsVisible = resultsContainer.style.display === 'block';

        if (!isResultsVisible || this.currentResults.length === 0) {
            return;
        }

        switch (event.key) {
            case 'ArrowDown':
                event.preventDefault();
                this.selectedIndex = Math.min(this.selectedIndex + 1, this.currentResults.length - 1);
                this.updateSelectedItem();
                break;
            case 'ArrowUp':
                event.preventDefault();
                this.selectedIndex = Math.max(this.selectedIndex - 1, -1);
                this.updateSelectedItem();
                break;
            case 'Enter':
                event.preventDefault();
                if (this.selectedIndex >= 0 && this.selectedIndex < this.currentResults.length) {
                    const selectedItem = this.currentResults[this.selectedIndex];
                    if (selectedItem.type === 'member') {
                        this.handleMemberClick(selectedItem);
                    } else if (selectedItem.type === 'group') {
                        this.handleGroupClick(selectedItem);
                    } else if (selectedItem.type === 'course') {
                        this.handleCourseClick(selectedItem);
                    }
                }
                break;
            case 'Escape':
                event.preventDefault();
                this.displayResults([]);
                break;
        }
    }

    updateSelectedItem() {
        const resultsList = this.shadowRoot.querySelector('.results-list');
        const items = resultsList.querySelectorAll('li');

        items.forEach((item, index) => {
            if (index === this.selectedIndex) {
                item.classList.add('selected');
                item.scrollIntoView({ block: 'nearest' });
            } else {
                item.classList.remove('selected');
            }
        });
    }

    async handleFocus() {
        if (!this.dataFetched && !this.isRequestInFlight) {
            await this.fetchMembers();
            this.dataFetched = true;
        }
    }

    async fetchMembers() {
        if (this.isRequestInFlight) {
            return;
        }

        this.isRequestInFlight = true;
        this.hideErrorMessage();
        this.showSpinner();

        // Create new AbortController for this request
        this.abortController = new AbortController();

        try {
            // Create timeout promise
            const timeoutPromise = new Promise((_, reject) => {
                setTimeout(() => reject(new Error('Request timeout')), 35000);
            });

            // Create fetch promise with abort signal
            const fetchPromise = fetch('/search-preload', {
                signal: this.abortController.signal,
                headers: {
                    'Content-Type': 'application/json'
                }
            });

            // Race between fetch and timeout
            const response = await Promise.race([fetchPromise, timeoutPromise]);

            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const json = await response.json();
            this.members = json["data"];
            this.dataFetched = true;

            // If user typed while fetch was in progress, perform the pending search
            if (this.pendingSearchQuery !== null) {
                const query = this.pendingSearchQuery;
                this.pendingSearchQuery = null;
                this.performSearch(query);
            }
        } catch (error) {
            if (error.name === 'AbortError') {
                console.log('Request was aborted');
                return;
            }

            console.error('Error fetching search preload:', error);
            this.showErrorMessage('Failed to load search data. Please refresh the page and try again.');
        } finally {
            this.isRequestInFlight = false;
            this.hideSpinner();
            this.abortController = null;
        }
    }

    handleSearch() {
        const input = this.shadowRoot.querySelector('input');
        const query = input.value.toLowerCase();

        // If there was an error, clear it and retry the API before searching
        const hadError = this.hadError === true;
        this.hideErrorMessage();

        if (hadError) {
            if (this.isRequestInFlight && this.abortController) {
                this.abortController.abort();
            }
            this.fetchMembers().then(() => {
                this.performSearch(query);
            });
            return;
        }

        // If we don't have data yet
        if (!this.dataFetched) {
            if (this.isRequestInFlight) {
                // Request already in flight, store query for when it completes
                this.pendingSearchQuery = query;
                return;
            }
            // No request in flight, start a new one
            this.fetchMembers().then(() => {
                this.performSearch(query);
            });
            return;
        }

        this.performSearch(query);
    }

    performSearch(query) {
        const results = this.members.filter(item => {
            if (item.type === 'member') {
                return (item.displayName?.toLowerCase().includes(query) || '') ||
                (item.username?.toLowerCase().includes(query) || '') ||
                (item.discourseUsername?.toLowerCase().includes(query) || '') ||
                (item.badgeNumber === query || '') ||
                (item.personalEmail === query || '') ||
                (item.phoneNumber === query || '');
            } else if (item.type === 'group') {
                return (item.displayName?.toLowerCase().includes(query) || '') ||
                (item.username?.toLowerCase().includes(query) || '') ||
                (item.description?.toLowerCase().includes(query) || '');
            } else if (item.type === 'course') {
                return (item.displayName?.toLowerCase().includes(query) || '') ||
                (item.description?.toLowerCase().includes(query) || '');
            }
            return false;
        });
        this.displayResults(results);
    }

    showSpinner() {
        // Clear any existing timeout
        if (this.spinnerTimeout) {
            clearTimeout(this.spinnerTimeout);
        }

        // Show spinner after 500ms delay
        this.spinnerTimeout = setTimeout(() => {
            const spinner = this.shadowRoot.querySelector('.spinner');
            if (spinner && this.isRequestInFlight) {
                spinner.style.display = 'block';
            }
        }, 500);
    }

    hideSpinner() {
        // Clear timeout if spinner hasn't been shown yet
        if (this.spinnerTimeout) {
            clearTimeout(this.spinnerTimeout);
            this.spinnerTimeout = null;
        }

        const spinner = this.shadowRoot.querySelector('.spinner');
        if (spinner) {
            spinner.style.display = 'none';
        }
    }

    showErrorMessage(message) {
        const errorElement = this.shadowRoot.querySelector('.error-message');
        if (errorElement) {
            errorElement.textContent = message;
            errorElement.style.display = 'block';
        }
        this.hadError = true;
    }

    hideErrorMessage() {
        const errorElement = this.shadowRoot.querySelector('.error-message');
        if (errorElement) {
            errorElement.style.display = 'none';
        }
        this.hadError = false;
    }

    displayResults(results) {
        const resultsList = this.shadowRoot.querySelector('.results-list');
        const resultsContainer = this.shadowRoot.querySelector('.results-container');
        const searchInputContainer = this.shadowRoot.querySelector('.search-input-container');

        resultsList.innerHTML = '';
        this.selectedIndex = -1;
        this.currentResults = results.slice(0, this.maxResults);

        if (this.currentResults.length > 0) {
            this.currentResults.forEach(item => {
                const li = document.createElement('li');
                if (item.type === 'member') {
                    const memberCard = document.createElement('dms-member-card');
                    memberCard.setAttribute('username', item.username);
                    memberCard.setAttribute('state', 'mini');
                    li.appendChild(memberCard);
                    li.addEventListener('click', () => this.handleMemberClick(item));
                } else if (item.type === 'group') {
                    const groupPill = document.createElement('dms-group-pill');
                    groupPill.setAttribute('name', item.displayName);
                    groupPill.setAttribute('slug', item.username);
                    li.appendChild(groupPill);
                    li.addEventListener('click', () => this.handleGroupClick(item));
                } else if (item.type === 'course') {
                    const name = document.createElement('span');
                    name.className = 'course-name';
                    name.textContent = item.displayName;
                    const label = document.createElement('span');
                    label.className = 'course-label';
                    label.textContent = 'Online course';
                    li.appendChild(name);
                    li.appendChild(label);
                    li.addEventListener('click', () => this.handleCourseClick(item));
                }
                resultsList.appendChild(li);
            });
            resultsContainer.style.display = 'block';
            searchInputContainer.classList.add('results-visible');
        } else {
            resultsContainer.style.display = 'none';
            searchInputContainer.classList.remove('results-visible');
        }
    }

    handleMemberClick(member) {
        const input = this.shadowRoot.querySelector('input');
        input.value = `${member.displayName} @${member.username}`;
        this.displayResults([]);
        window.location.href = `/profile/@${member.username}`;
    }

    handleGroupClick(group) {
        const input = this.shadowRoot.querySelector('input');
        input.value = `${group.displayName} (Group)`;
        this.displayResults([]);
        window.location.href = `/groups/${group.username}`;
    }

    handleCourseClick(course) {
        this.displayResults([]);
        if (course.url && /^https:\/\//i.test(course.url)) {
            window.open(course.url, '_blank', 'noopener');
        }
    }

    clearSearch() {
        const input = this.shadowRoot.querySelector('input');
        input.value = '';
        this.toggleClearButton();
        this.displayResults([]);
        this.hideErrorMessage();
    }

    toggleClearButton() {
        const input = this.shadowRoot.querySelector('input');
        const clearButton = this.shadowRoot.querySelector('.clear-button');
        clearButton.style.display = input.value ? 'block' : 'none';
    }

    handleOutsideClick(event) {
        const searchContainer = this.shadowRoot.querySelector('.dms-search-container');
        if (!searchContainer.contains(event.target)) {
            this.displayResults([]);
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

customElements.define('dms-search', DMSSearch);