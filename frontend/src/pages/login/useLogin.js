import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  adminLogin,
  completeSignIn,
  login,
} from "../../services/authService.js";
import { useAuth } from "../../context/useAuth.js";
import { validatePasswordChange } from "../profile/passwordValidation.js";

// Validate credentials against the required fields and backend length limits.
export function validateLogin(username, password) {
  const errors = {};
  if (!username.trim()) errors.username = "Enter your GPF number or username.";
  else if (username.trim().length > 64)
    errors.username = "GPF number or username must be 64 characters or fewer.";
  if (!password) errors.password = "Enter your password.";
  else if (new TextEncoder().encode(password).length > 72)
    errors.password = "Password exceeds the supported length (72 UTF-8 bytes).";
  return errors;
}

// The new password that replaces a temporary or forgotten one.
export function validateNewPassword(values) {
  return validatePasswordChange({ ...values, currentPassword: "-" });
}

// Sign-in in two steps when needed: the password, then a new password when the
// current one is temporary.
export default function useLogin(admin = false) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [challenge, setChallenge] = useState(null);
  const [passwords, setPasswords] = useState({
    newPassword: "",
    confirmPassword: "",
  });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(false);
  const inFlight = useRef(false);
  const mounted = useRef(false);
  const navigate = useNavigate();
  const { signIn } = useAuth();

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Update a field and clear its prior error.
  function handleChange(event) {
    const { name, value } = event.target;
    if (name === "username") setUsername(value);
    else if (name === "password") setPassword(value);
    else if (name in passwords)
      setPasswords((previous) => ({ ...previous, [name]: value }));
    else return;
    setErrors((previous) => ({ ...previous, [name]: undefined }));
    setError("");
  }

  // Run one request at a time, showing field or general errors.
  async function run(work) {
    if (inFlight.current) return;
    inFlight.current = true;
    setLoading(true);
    setError("");
    setNotice("");
    try {
      await work();
    } catch (failure) {
      if (mounted.current) {
        if (failure.fieldErrors) setErrors(failure.fieldErrors);
        else
          setError(failure.message || "Unable to sign in. Please try again.");
        // A new-password step that took too long starts over.
        if (failure.code === "NO_PENDING_SIGN_IN") setChallenge(null);
      }
    } finally {
      inFlight.current = false;
      if (mounted.current) setLoading(false);
    }
  }

  function finish(user) {
    setPassword("");
    setPasswords({ newPassword: "", confirmPassword: "" });
    signIn(user);
    navigate(admin ? "/admin" : "/chat", { replace: true });
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (inFlight.current) return;
    const validation = validateLogin(username, password);
    setErrors(validation);
    if (Object.keys(validation).length) return;
    await run(async () => {
      const credentials = { username: username.trim(), password };
      const result = await (admin ? adminLogin : login)(credentials);
      if (!mounted.current) return;
      if (result.user) finish(result.user);
      else {
        setPassword("");
        setChallenge(result.challenge);
      }
    });
  }

  async function handleVerify(event) {
    event.preventDefault();
    if (inFlight.current) return;
    const validation = validateNewPassword(passwords);
    setErrors(validation);
    if (Object.keys(validation).length) return;
    await run(async () => {
      const user = await completeSignIn(passwords);
      if (mounted.current) finish(user);
    });
  }

  return {
    username,
    password,
    showPassword,
    challenge,
    passwords,
    errors,
    error,
    notice,
    loading,
    handleChange,
    handleSubmit,
    handleVerify,
    startOver: () => {
      setChallenge(null);
      setErrors({});
      setError("");
      setNotice("");
    },
    togglePassword: () => setShowPassword((previous) => !previous),
  };
}
