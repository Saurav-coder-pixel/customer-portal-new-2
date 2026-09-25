# Project Commands and Guidelines

## Build & Run Commands
* **Frontend (Angular)**: `cd angular-frontend && npm start` (runs on `http://localhost:4200`, proxies `/api` to port 8080)
* **Frontend Build**: `cd angular-frontend && npm run build`
* **Java Backend (Spring Boot + Oracle)**: `mvn clean compile`, `mvn spring-boot:run` (runs on `http://localhost:8080`)

## Code Style & Conventions
* **Java**: Follow standard Java and Spring Boot conventions. Use standard annotations and rely on existing exception handling (like `GlobalExceptionHandler`).
* **TypeScript / Angular**: Use strict typing. Prefer `camelCase` for variables/methods and `PascalCase` for classes/components. Use Reactive Forms for validated workflows and `HttpClient` for API interaction.
* **Error Handling**: Centralize errors where possible and provide meaningful error messages in HTTP responses.

## General Instructions
* Keep explanations concise and focus on providing clean, working code.
* Break down large changes into smaller, testable functions or components.
* Write meaningful commit messages if asked to commit.
* Avoid leaving dead or commented-out code.
