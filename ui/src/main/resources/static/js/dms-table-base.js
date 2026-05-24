import { LitElement, html, css } from 'https://esm.sh/lit@3.3.0';
import { unsafeHTML } from 'https://esm.sh/lit@3.3.0/directives/unsafe-html.js';
import { fetchMember } from './dms-member-service.js';

/**
 * DmsTableBase - A base table component for rendering both regular and metadata tables.
 *
 * @element dms-table-base
 * @prop {Array} headers - Column headers for regular tables (not used for metadata)
 * @prop {Array} rows - Data rows. For regular tables: array of arrays. For metadata: array of [key, value] pairs.
 * @prop {Boolean} isMetadata - Whether to render as a metadata table (key-value pairs)
 * @prop {Boolean} filterable - Whether to enable filtering for the table
 * @prop {Boolean} paginated - Whether to enable pagination for the table
 * @prop {Number} pageSize - Number of rows per page (default: 20)
 * @prop {Number} paginateAfter - Enable pagination automatically when rows exceed this number (default: 20)
 */
class DmsTableBase extends LitElement {
    static properties = {
        headers: { type: Array },
        rows: { type: Array },
        isMetadata: { type: Boolean },
        filterable: { type: Boolean },
        paginated: { type: Boolean },
        pageSize: { type: Number },
        paginateAfter: { type: Number },
        _filterText: { type: String, state: true },
        _filteredRows: { type: Array, state: true },
        _currentPage: { type: Number, state: true },
        _totalPages: { type: Number, state: true }
    };

    static styles = css`
        .table-container {
            overflow-x: auto;
            margin-top: 16px;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            margin-top: 16px;
            border: 1px solid #ddd;
        }
        th, td {
            border: 1px solid #ddd;
            padding: 8px;
            text-align: left;
            vertical-align: top;
        }
        th {
            background-color: #f2f2f2;
        }
        .metadata-table td:first-child {
            font-weight: bold;
            width: 150px;
            background-color: #f2f2f2;
        }
        .metadata-table pre {
            white-space: pre-wrap;
            white-space: -moz-pre-wrap;
            white-space: -pre-wrap;
            white-space: -o-pre-wrap;
            word-wrap: break-word;
            margin: 0;
            font-family: monospace;
        }
        .filter-container {
            display: flex;
            align-items: center;
            margin-bottom: 8px;
            position: relative;
            max-width: 300px;
            margin-left: auto; /* Align to the right */
        }
        .filter-input {
            flex: 1;
            padding: 8px;
            border: 1px solid #ddd;
            border-radius: 4px;
            font-size: 14px;
            width: 100%;
        }
        .clear-filter {
            position: absolute;
            right: 8px;
            background: none;
            border: none;
            cursor: pointer;
            font-weight: bold;
            color: #777;
        }
        .clear-filter:hover {
            color: #333;
        }
        .empty-state {
            padding: 20px;
            text-align: center;
            color: #666;
            font-style: italic;
            border: 1px solid #ddd;
            margin-top: 16px;
        }
        .pagination-container {
            display: flex;
            justify-content: center;
            align-items: center;
            margin-top: 16px;
            flex-wrap: wrap;
        }
        .pagination-button {
            padding: 6px 12px;
            margin: 0 4px;
            background-color: #f8f8f8;
            border: 1px solid #ddd;
            border-radius: 4px;
            cursor: pointer;
            font-size: 14px;
            min-width: 32px;
            text-align: center;
        }
        .pagination-button:hover:not(.active, .disabled) {
            background-color: #e8e8e8;
        }
        .pagination-button.active {
            background-color: #4a90e2;
            color: white;
            border-color: #4a90e2;
        }
        .pagination-button.disabled {
            color: #999;
            cursor: not-allowed;
        }
        .pagination-info {
            margin: 0 8px;
            font-size: 14px;
            color: #666;
        }
        .badge {
            display: inline-flex;
            align-items: center;
            gap: 4px;
            padding: 4px 8px;
            border-radius: 4px;
            font-size: 12px;
            font-weight: 500;
        }
        .badge-success {
            background-color: #d4edda;
            color: #155724;
        }
        .badge-danger {
            background-color: #f8d7da;
            color: #721c24;
        }
        .badge-warning {
            background-color: #fff3cd;
            color: #856404;
        }
        .badge-info {
            background-color: #d1ecf1;
            color: #0c5460;
        }
        .badge .material-symbols-outlined {
            font-size: 16px;
        }
        .cell-link {
            color: #4a90e2;
            text-decoration: none;
        }
        .cell-link:hover {
            color: #357abd;
            text-decoration: underline;
        }
        @media (max-width: 768px) {
            .filter-container {
                max-width: 100%;
            }
            .pagination-container {
                justify-content: center;
            }
            .pagination-info {
                flex-basis: 100%;
                text-align: center;
                margin-bottom: 8px;
            }
        }
    `;

    constructor() {
        super();
        this.headers = [];
        this.rows = [];
        this.isMetadata = false;
        this.filterable = false;
        this.paginated = false;
        this.pageSize = 20;
        this.paginateAfter = 20;
        this._filterText = '';
        this._filteredRows = [];
        this._currentPage = 1;
        this._totalPages = 1;
    }

    updated(changedProperties) {
        if (changedProperties.has('rows') || changedProperties.has('_filterText')) {
            this._filterRows();
            this._updatePagination();
        }

        if (changedProperties.has('_filteredRows') ||
        changedProperties.has('pageSize') ||
        changedProperties.has('_currentPage')) {
            this._updatePagination();
        }

        if (this.filterable && changedProperties.has('rows')) {
            this._eagerFetchMembers();
        }
    }

    _eagerFetchMembers() {
        for (const row of this.rows) {
            for (const cell of row) {
                if (cell !== null && typeof cell === 'object' && 'username' in cell && cell.username) {
                    fetchMember(cell.username);
                }
            }
        }
    }

    _filterRows() {
        if (!this._filterText || this._filterText.trim() === '') {
            this._filteredRows = this.rows;
            return;
        }

        const searchTerm = this._filterText.toLowerCase();

        if (this.isMetadata) {
            this._filteredRows = this.rows.filter(([key, value]) => {
                const keyStr = String(key).toLowerCase();
                const valueStr = String(value).toLowerCase();
                return keyStr.includes(searchTerm) || valueStr.includes(searchTerm);
            });
        } else {
            this._filteredRows = this.rows.filter(row => {
                return row.some(cell => {
                    if (cell === null) return false;
                    // Handle LINK objects - search in text and url
                    if (typeof cell === 'object' && 'url' in cell) {
                        const text = (cell.text || '').toLowerCase();
                        const url = (cell.url || '').toLowerCase();
                        return text.includes(searchTerm) || url.includes(searchTerm);
                    }
                    // Handle MEMBER objects - search in username and displayName
                    if (typeof cell === 'object' && 'username' in cell) {
                        const username = (cell.username || '').toLowerCase();
                        const displayName = (cell.displayName || '').toLowerCase();
                        return username.includes(searchTerm) || displayName.includes(searchTerm);
                    }
                    return String(cell).toLowerCase().includes(searchTerm);
                });
            });
        }
    }

    _updatePagination() {
        const rowsToUse = this._filteredRows.length > 0 ? this._filteredRows : this.rows;
        this._totalPages = Math.max(1, Math.ceil(rowsToUse.length / this.pageSize));

        // Reset current page if out of bounds
        if (this._currentPage > this._totalPages) {
            this._currentPage = 1;
        }
    }

    _changePage(newPage) {
        if (newPage >= 1 && newPage <= this._totalPages) {
            this._currentPage = newPage;
            // Scroll to top of table when changing page
            setTimeout(() => {
                const table = this.shadowRoot.querySelector('table');
                if (table) {
                    table.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
            }, 0);
        }
    }

    _handleFilterInput(e) {
        this._filterText = e.target.value;
    }

    _handleFilterFocus(e) {
        // Check if on mobile device
        if (window.innerWidth <= 768) {
            // Scroll the filter to the top of the viewport with some padding
            setTimeout(() => {
                const filterContainer = this.shadowRoot.querySelector('.filter-container');
                if (filterContainer) {
                    filterContainer.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    // Add a little extra padding at the top
                    window.scrollBy(0, -15);
                }
            }, 100);
        }
    }

    _clearFilter() {
        this._filterText = '';
        const input = this.shadowRoot.querySelector('.filter-input');
        if (input) input.value = '';
    }

    render() {
        const hasFilteredData = this._filterText && this._filteredRows.length === 0;
        const rowsToUse = this._filteredRows.length > 0 ? this._filteredRows : this.rows;

        // Determine if pagination should be shown
        const shouldPaginate = this.paginated || (!this.paginated && rowsToUse.length > this.paginateAfter);

        return html`
            <div class="table-container">
                ${this.filterable ? html`
                    <div class="filter-container">
                        <input
                            type="text"
                            class="filter-input"
                            placeholder="Type to filter"
                            .value="${this._filterText}"
                            @input="${this._handleFilterInput}"
                            @focus="${this._handleFilterFocus}">
                        ${this._filterText ? html`
                            <button class="clear-filter" @click="${this._clearFilter}">×</button>
                        ` : ''}
                    </div>
                ` : ''}
                ${hasFilteredData ? html`
                    <div class="empty-state">
                        No records match the filter. Clear the filter to see all records.
                    </div>
                ` : html`
                    <table class="${this.isMetadata ? 'metadata-table' : ''}">
                        ${this.isMetadata ? this._renderMetadataTable() : this._renderRegularTable()}
                    </table>

                    ${shouldPaginate && this._totalPages > 1 ? this._renderPagination() : ''}
                `}
            </div>
        `;
    }

    _renderPagination() {
        const rowsToUse = this._filteredRows.length > 0 ? this._filteredRows : this.rows;
        const startIndex = (this._currentPage - 1) * this.pageSize + 1;
        const endIndex = Math.min(startIndex + this.pageSize - 1, rowsToUse.length);

        // Generate page buttons - show first, last, current, and adjacent pages
        const pageButtons = [];

        // Add first page
        pageButtons.push(this._renderPageButton(1));

        // Add ellipsis if needed
        if (this._currentPage > 3) {
            pageButtons.push(html`<span class="pagination-button disabled">...</span>`);
        }

        // Add adjacent pages
        for (let i = Math.max(2, this._currentPage - 1); i <= Math.min(this._totalPages - 1, this._currentPage + 1); i++) {
            if (i === 1 || i === this._totalPages) continue; // Skip first and last (added separately)
            pageButtons.push(this._renderPageButton(i));
        }

        // Add ellipsis if needed
        if (this._currentPage < this._totalPages - 2) {
            pageButtons.push(html`<span class="pagination-button disabled">...</span>`);
        }

        // Add last page if more than one page
        if (this._totalPages > 1) {
            pageButtons.push(this._renderPageButton(this._totalPages));
        }

        return html`
            <div class="pagination-container">
                <button
                    class="pagination-button ${this._currentPage === 1 ? 'disabled' : ''}"
                    @click="${() => this._changePage(this._currentPage - 1)}"
                    ?disabled="${this._currentPage === 1}"
                >
                    &lt;
                </button>

                ${pageButtons}

                <button
                    class="pagination-button ${this._currentPage === this._totalPages ? 'disabled' : ''}"
                    @click="${() => this._changePage(this._currentPage + 1)}"
                    ?disabled="${this._currentPage === this._totalPages}"
                >
                    &gt;
                </button>

                <span class="pagination-info">
                    Showing ${startIndex}-${endIndex} of ${rowsToUse.length} entries
                </span>
            </div>
        `;
    }

    _renderPageButton(pageNum) {
        return html`
            <button
                class="pagination-button ${this._currentPage === pageNum ? 'active' : ''}"
                @click="${() => this._changePage(pageNum)}"
            >
                ${pageNum}
            </button>
        `;
    }

    _renderCell(cell) {
        // Check if cell is a LINK object (has text and url properties)
        if (cell !== null && typeof cell === 'object' && 'url' in cell) {
            const text = cell.text || cell.url;
            const url = cell.url || '';

            // Return an anchor tag with consistent styling
            return html`<a href="${url}" target="_blank" rel="noopener noreferrer" class="cell-link">${text}</a>`;
        }

        // Check if cell is a MEMBER object (has username property)
        if (cell !== null && typeof cell === 'object' && 'username' in cell) {
            const username = cell.username || '';
            return html`<dms-member-card username="${username}" state="mini"></dms-member-card>`;
        }

        // Check if cell is a BADGE object (has variant and text properties)
        if (cell !== null && typeof cell === 'object' && 'variant' in cell) {
            const variant = cell.variant || 'info';
            const text = cell.text || '';
            const icon = cell.icon || '';
            const tooltip = cell.tooltip || '';

            return html`<span class="badge badge-${variant}" title="${tooltip}">
                ${icon ? html`<span class="material-symbols-outlined">${icon}</span>` : ''}
                ${text}
            </span>`;
        }

        // Check if cell is a RELATIVE_DATE object
        if (cell !== null && typeof cell === 'object' && cell._type === 'RELATIVE_DATE') {
            const timestamp = cell.timestamp || '';
            return html`<dms-relative-date timestamp="${timestamp}"></dms-relative-date>`;
        }

        // Default rendering for other types
        return cell === null ? 'null' : cell;
    }

    _renderRegularTable() {
        const rowsToUse = this._filteredRows.length > 0 ? this._filteredRows : this.rows;
        const shouldPaginate = this.paginated || (!this.paginated && rowsToUse.length > this.paginateAfter);

        // Apply pagination to rows if needed
        let visibleRows = rowsToUse;
        if (shouldPaginate) {
            const startIdx = (this._currentPage - 1) * this.pageSize;
            const endIdx = Math.min(startIdx + this.pageSize, rowsToUse.length);
            visibleRows = rowsToUse.slice(startIdx, endIdx);
        }

        return html`
            <thead>
                <tr>
                    ${this.headers.map(header => html`<th>${header}</th>`)}
                </tr>
            </thead>
            <tbody>
                ${visibleRows.map(row => html`
                    <tr>
                        ${row.map(cell => html`<td>${this._renderCell(cell)}</td>`)}
                    </tr>
                `)}
            </tbody>
        `;
    }

    _renderMetadataTable() {
        const rowsToUse = this._filteredRows.length > 0 ? this._filteredRows : this.rows;
        const shouldPaginate = this.paginated || (!this.paginated && rowsToUse.length > this.paginateAfter);

        // Apply pagination to rows if needed
        let visibleRows = rowsToUse;
        if (shouldPaginate) {
            const startIdx = (this._currentPage - 1) * this.pageSize;
            const endIdx = Math.min(startIdx + this.pageSize, rowsToUse.length);
            visibleRows = rowsToUse.slice(startIdx, endIdx);
        }

        const renderMetadataValue = (key, value) => {
            if (key === 'SQL Query') {
                return html`<pre>${value}</pre>`;
            }
            if (typeof value === 'string' && /^https?:\/\//.test(value)) {
                return html`<a href="${value}" target="_blank" rel="noopener noreferrer">${value}</a>`;
            }
            return value;
        };

        return html`
            <tbody>
                ${visibleRows.map(([key, value]) => html`
                    <tr>
                        <td>${key}</td>
                        <td>${renderMetadataValue(key, value)}</td>
                    </tr>
                `)}
            </tbody>
        `;
    }
}

customElements.define('dms-table-base', DmsTableBase);
export { DmsTableBase };
