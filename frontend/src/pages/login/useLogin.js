import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { login } from "../../services/authService.js";
import { useAuth } from "../../context/useAuth.js";

// Validate credentials against the required fields and backend length limits.
export function validateLogin(username, password) {
  const errors = {};
  if (!username.trim()) errors.username = "Enter your username.";
  else if (username.trim().length > 64)
    errors.username = "Username must be 64 characters or fewer.";
  if (!password) errors.password = "Enter your password.";
  else if (new TextEncoder().encode(password).length > 72)
    errors.password = "Password exceeds the supported length (72 UTF-8 bytes).";
  return errors;
}

// Manage login form state and authentication actions.
export default function useLogin() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
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

  // Update a credential field and clear its prior error.
  // function is called when the user types in the username or password input fields. It updates the corresponding state and clears any previous error messages for that field.
  function handleChange(event) {
    const { name, value } = event.target;
    if (name === "username") setUsername(value);
    else if (name === "password") setPassword(value);
    else return;
    setErrors((previous) => ({ ...previous, [name]: undefined }));
    setError("");
  }

  // Validate and submit credentials, then navigate after a successful login.
  async function handleSubmit(event) {
    event.preventDefault();
    //initially inFlight is false, so it will not return and will continue to execute the code below. Once the user clicks on the submit button, inFlight will be set to true and it will prevent multiple submissions while the login request is in progress.
    if (inFlight.current) return;
    const validation = validateLogin(username, password);
    setErrors(validation);
    setError("");
    if (Object.keys(validation).length) return;

    inFlight.current = true;
    setLoading(true);
    try {
      const user = await login({ username: username.trim(), password });
      if (!mounted.current) return;
      setPassword("");
      signIn(user);
      navigate("/chat", { replace: true });
    } catch (failure) {
      if (mounted.current) {
        if (failure.fieldErrors) setErrors(failure.fieldErrors);
        else
          setError(failure.message || "Unable to sign in. Please try again.");
      }
    } finally {
      inFlight.current = false;
      if (mounted.current) setLoading(false);
    }
  }

  return {
    username,
    password,
    showPassword,
    errors,
    error,
    loading,
    handleChange,
    handleSubmit,
    togglePassword: () => setShowPassword((previous) => !previous),
  };
}
