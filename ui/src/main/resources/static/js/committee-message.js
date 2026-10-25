(function () {
    const modal = document.getElementById('committeeMessageModal');
    if (!modal) return;

    const formatTime = (iso) =>
        new Date(iso).toLocaleString([], {weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'});

    const next = document.getElementById('committeeMessageNext');
    if (next && modal.dataset.nextAllowed) next.textContent = formatTime(modal.dataset.nextAllowed);

    const form = document.getElementById('committeeMessageForm');
    if (!form) return;

    const text = document.getElementById('committeeMessageText');
    const count = document.getElementById('committeeMessageCount');
    const urlInput = document.getElementById('committeeThreadUrl');
    const sendBtn = document.getElementById('committeeMessageSend');
    const errorBox = document.getElementById('committeeMessageError');
    const done = document.getElementById('committeeMessageDone');
    const max = parseInt(modal.dataset.max, 10) || 120;
    let sent = false;

    const mode = () => (form.querySelector('input[name="committeeMessageThread"]:checked') || {}).value;

    const refresh = () => {
        const left = max - Array.from(text.value).length;
        count.textContent = left + ' left';
        count.classList.toggle('text-danger', left < 10);
        urlInput.hidden = mode() !== 'existing';
        urlInput.disabled = mode() !== 'existing';
    };

    const showError = (message) => {
        errorBox.textContent = message;
        errorBox.hidden = !message;
    };

    text.addEventListener('input', refresh);
    form.addEventListener('change', refresh);
    refresh();

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        showError('');
        const body = {message: text.value, createThread: mode() === 'new'};
        if (mode() === 'existing') body.talkUrl = urlInput.value;
        sendBtn.disabled = true;
        const label = sendBtn.innerHTML;
        sendBtn.textContent = 'Sending…';
        try {
            const response = await fetch(modal.dataset.url, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body),
            });
            const result = await response.json().catch(() => ({}));
            if (response.ok) {
                sent = true;
                form.hidden = true;
                sendBtn.hidden = true;
                const data = result.data || {};
                document.getElementById('committeeMessageRecipients').textContent =
                    data.recipients + (data.recipients === 1 ? ' follower' : ' followers');
                const link = document.getElementById('committeeMessageLink');
                link.href = data.shortUrl;
                link.textContent = data.shortUrl;
                done.hidden = false;
                return;
            }
            let message = result.message || 'Something went wrong. Please try again.';
            if (response.status === 429 && result.data && result.data.nextAllowedAt) {
                message += '. You can send again after ' + formatTime(result.data.nextAllowedAt) + '.';
            }
            showError(message);
        } catch (e) {
            showError('Could not reach the server. Please try again.');
        }
        sendBtn.disabled = false;
        sendBtn.innerHTML = label;
    });

    // Reload after a send so the quota shown on the next open is current.
    modal.addEventListener('hidden.bs.modal', () => {
        if (sent) window.location.reload();
    });
})();
