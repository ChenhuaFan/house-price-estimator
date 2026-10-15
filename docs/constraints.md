# Project Constraints

This document records the technical, architectural, quality, and delivery constraints that must be followed during implementation. It intentionally excludes product feature requirements. Its content is derived from [`requirements/Interview Tasks Fullstack.md`](../requirements/Interview%20Tasks%20Fullstack.md). If this document conflicts with the original requirements, the original requirements take precedence.

## 1. Technology Stack and Versions

The baseline uses the lowest version line explicitly permitted by the source document. Versions omitted by the source are inferred from stable releases available when the source PDF was created in April 2025 and from the compatibility requirements of the selected frameworks.

| Scope | Technology | Baseline Version | Basis |
| --- | --- | --- | --- |
| Machine learning runtime | Python | 3.12.x | Source minimum; use the latest available 3.12 patch release |
| Machine learning API | FastAPI | 0.115.12 | Inferred stable release available in March 2025 |
| ASGI server | Uvicorn | 0.34.0 | Inferred compatible runtime for FastAPI 0.115.12 |
| Machine learning | Scikit-learn | 1.6.1 | Inferred stable line with Python 3.12 support |
| Frontend runtime | Node.js | 20.x, minimum 20.9.0 | Inferred LTS runtime compatible with Next.js 15 |
| Unified portal | Next.js | 15.2.4 | Inferred stable App Router release available before the source date |
| UI runtime | React and React DOM | 19.0.0 | Required by the selected Next.js 15 App Router baseline |
| Frontend language | TypeScript | 5.1.0 or later within 5.x | Inferred compatible baseline for the selected Next.js line |
| Frontend styling | Tailwind CSS | 4.0.0 | Inferred stable major release available before the source date |
| Property Value Estimator backend | Python | Same Python 3.12.x runtime | Must integrate with the Task 1 regression model container |
| Property Market Analysis runtime | Java | 21 | Source requirement; use a maintained Java 21 patch release |
| Property Market Analysis framework | Spring Boot | 3.4.4 | Exact source requirement |
| Java build system | Maven | 3.6.3 or later | Inferred minimum supported by Spring Boot 3.4.x |

### Version Enforcement Policy

- Python must remain on the 3.12 release line; Java must remain on the Java 21 release line.
- Spring Boot must remain at 3.4.4 unless the source constraint is explicitly revised.
- Direct application dependencies must be pinned to the baseline versions in their manifests when each application is created.
- Generated lockfiles must be committed and used for reproducible installs.
- Patch-level security and defect fixes may be applied within the selected release line without changing the architectural baseline. Major-version changes require a documented technical decision.

## 2. Model and Container Constraints

- Housing price prediction must use a simple regression model.
- The model must operate on the supplied housing dataset and its provided features.
- The model service must be containerised and include a `Dockerfile`.
- Both Task 2 applications must integrate with the same machine learning model container produced in Task 1.

## 3. System Architecture Constraints

- A single, unified Next.js portal must host two independent applications.
- The two applications must use different backend technologies:
  - The Property Value Estimator must use a Python backend.
  - The Property Market Analysis application must use a Java backend.
- Both applications must share a layout and navigation and use a consistent design system.
- Routing within and between the applications must use the Next.js App Router.
- Initial data loading must use React Server Components.
- Server Components and Client Components must be divided appropriately according to their responsibilities.
- Shared functionality must be implemented through custom hooks.
- The Next.js codebase must be structured according to Next.js best practices.

## 4. Data Flow, State, and Reliability Constraints

- Appropriate client-side state management must be implemented.
- Form state must be managed effectively and include validation.
- Efficient and appropriate data-fetching strategies must be used.
- API communication must handle error states correctly.
- Loading and error states must be handled at the shared layout level.
- The UI must use appropriate loading states and error boundaries.
- The Python backend must implement data validation and error handling.
- The Java backend must implement caching for performance optimisation.

## 5. UI and Accessibility Constraints

- Responsive layouts must be implemented with Tailwind CSS.
- UI components must follow WCAG accessibility guidelines. The original document does not specify a WCAG version or conformance level.
- Transitions between pages and application states must be smooth.
- Both applications must use a cohesive UI that follows modern design principles.

## 6. Delivery and Demonstration Constraints

- Source code must be hosted on GitHub.
- Task 1 must be available for a live demonstration through Swagger/OpenAPI.
- Task 2 must be available for a live demonstration during the interview.
- The Task 1 model must be built, containerised, and deployed.

## 7. Remaining Open Technical Decisions

The inferred version baseline above is now a project constraint. The following items remain undecided and must not be assumed without a separate technical decision:

- JavaScript package manager and monorepo tooling.
- Database, persistence strategy, and caching product.
- Cloud provider, deployment platform, and runtime environment.
- Authentication, authorisation, and API security approach.
- Automated testing frameworks, coverage thresholds, and CI/CD platform.
- Specific WCAG version and conformance level.
