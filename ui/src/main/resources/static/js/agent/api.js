export const API = '/api/agent';

/** Calls the agent API as the signed-in member; never throws, always returns {ok, status, data, message}. */
export async function api(method, path, body) {
    let res;
    try {
        res = await fetch(API + path, {
            method,
            credentials: 'same-origin',
            headers: body ? {'Content-Type': 'application/json'} : {},
            body: body ? JSON.stringify(body) : undefined,
        });
    } catch (e) {
        return {ok: false, status: 0, json: null, data: null, message: 'Could not reach the server.'};
    }
    let json = null;
    try {
        json = await res.json();
    } catch (e) { /* empty body */ }
    return {ok: res.ok, status: res.status, json, data: json && json.data, message: json && json.message};
}
