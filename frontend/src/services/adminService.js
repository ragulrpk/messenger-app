import { csrfHeaders, request } from "./authService";

const loginUrl = import.meta.env?.VITE_AUTH_LOGIN_URL || "/api/v1/users/login";
const base = loginUrl.replace(/\/users\/login\/?$/, "/admin");

const read = (path) => request(`${base}${path}`);
// Every change sends a fresh CSRF token with the session cookie.
async function change(path, body = {}) {
  const csrf = await csrfHeaders(loginUrl);
  return request(`${base}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...csrf },
    body: JSON.stringify(body),
  });
}
const query = (q, offset) =>
  `?q=${encodeURIComponent(q)}&offset=${encodeURIComponent(offset)}`;

// People: { items, hasMore } pages of 50.
export const listPeople = (q = "", offset = 0) =>
  read(`/people${query(q, offset)}`);
// { person, signIns }
export const getPerson = (id) => read(`/people/${id}`);
// { loginId, temporaryPassword }: shown once, never stored.
export const createPerson = (details) => change("/people", details);
export const updatePerson = (id, details) => change(`/people/${id}`, details);
export const setPersonEnabled = (id, enabled) =>
  change(`/people/${id}/enabled`, { enabled });
export const setPersonAdministrator = (id, administrator) =>
  change(`/people/${id}/administrator`, { administrator });
export const signOutPerson = (id) => change(`/people/${id}/sign-out`);
export const requirePasswordReset = (id) =>
  change(`/people/${id}/password-reset`);
// A CSV without passwords; returns { imported, skipped: [{ line, reason }] }.
export async function bulkAddPeople(file) {
  const csrf = await csrfHeaders(loginUrl);
  const form = new FormData();
  form.set("file", file);
  return request(`${base}/people/bulk`, {
    method: "POST",
    headers: csrf,
    body: form,
  });
}

// Groups: { items, hasMore }; details are { group, members }.
export const listGroups = (q = "", offset = 0) =>
  read(`/groups${query(q, offset)}`);
export const getGroup = (id) => read(`/groups/${id}`);
export const createGroup = (group) => change("/groups", group);
export const addGroupMembers = (id, userIds) =>
  change(`/groups/${id}/members`, { userIds });
export const removeGroupMember = (id, userId) =>
  change(`/groups/${id}/members/${userId}/remove`);
export const setGroupRole = (id, userId, role) =>
  change(`/groups/${id}/members/${userId}/role`, { role });
export const renameGroup = (id, name) =>
  change(`/groups/${id}/rename`, { name });
export const setGroupAnnouncements = (id, adminsOnly) =>
  change(`/groups/${id}/settings`, { adminsOnly });
export const setGroupArchived = (id, archived) =>
  change(`/groups/${id}/archive`, { archived });

export const getAuditLog = () => read("/audit");
export const getSignIns = () => read("/sign-ins");
