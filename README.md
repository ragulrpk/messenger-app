# Messenger

The React frontend, Spring Boot backend, CI workflow, container configuration and documentation are managed by one Git repository rooted at `messenger-app`.

## Layout

```text
messenger-app/
  .github/                  GitHub Actions and dependency updates
  frontend/                 React application and frontend container builds
  backend/demo/             Spring Boot application and Maven wrapper
  compose.dev.yaml           Development containers
  compose.windows.yml        Containers using Windows PostgreSQL
  compose.production.yml     Production container configuration
  .env.*.example             Shareable environment templates
```

Run Git commands from this directory. Neither application directory is a separate repository or submodule. The existing nested ignore and attribute files remain applicable to their directories.

## Development and verification

The CI configuration uses Node.js 24 and Java 25. Frontend commands run from `frontend`: `npm ci`, `npm test`, `npm run lint`, `npm run format:check`, and `npm run build`.

Backend commands run from `backend/demo`: `./mvnw verify` and `./mvnw -Pintegration-tests verify`. On Windows use `mvnw.cmd`, or an installed Maven executable. The integration profile requires Docker for PostgreSQL and Redis.

See the frontend and backend README files for application-specific instructions. Root-level `WINDOWS-DOCKER.md` documents Windows setup; `PRODUCTION.md` documents production deployment and operations. The dated project review records the assessment before this repository restructuring, not a production certification.

## Configuration and repository hygiene

Copy the appropriate `.env.*.example` template to a local environment file and configure it for your environment. Actual `.env` files, dependencies, build output and machine-specific IDE settings are ignored. Environment example files, Maven wrapper configuration and the frontend lockfile are included.

The repository has not been published. Configure a Git remote and create the initial commit when ready; the GitHub Actions workflow will run on a push or pull request after publication.
