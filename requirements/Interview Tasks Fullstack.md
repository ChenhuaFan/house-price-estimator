# Interview Tasks

## Task 1: Housing Price Prediction Model API

### Objective

Build, containerise, and deploy a simple regression model that predicts housing prices based on provided features (dataset attached).

### Requirements

- Api Endpoints:
  - predict - Accepts housing features and returns price predictions. Both single and batch numbers
  - model-info - Returns model coefficients and performance metrics
  - health - Simple health check endpoint

### Technical Constraints

- Python 3.12+
- FastAPI
- Scikit-learn

### Deliverables

1. Source code in github
2. Dockerfile
3. Ability to show this live during interview via (Swagger/OpenAPI)

## Task 2: Multi-Application Next.js Portal

### Objective

Create a unified Next.js portal that hosts two independent applications with different backend technologies, both capable of interacting with the ML model from Task 1.

### Portal Structure Requirements

1. Unified Navigation & Layout:
   1. Implement a shared layout with navigation between applications
   2. Use Next.js App Router for routing between and within applications
   3. Create a consistent design system across both applications
   4. Properly handle loading and error states at the layout level
2. App 1: Property Value Estimator (Python Backend)
   1. Frontend:
      1. Create a form for inputting property details (all fields from the model)
      2. Implement client-side validation with appropriate error messages
      3. Display prediction results in both tabular format and visual chart
      4. Implement a history feature showing previous estimates
      5. Create a comparison view to analyse multiple properties side-by-side
   2. Backend (Python):
      1. Handle form submissions
      2. Integrate with regression model container from Task 1
      3. Implement data validation and error handling
3. App 2: Property Market Analysis (Java Backend)
   1. Frontend:
      1. Create an interactive dashboard with property market visualisations
      2. Implement filters for analysing different property segments
      3. Build a "what-if" analysis tool using the model
      4. Provide data export options (CSV, PDF)
      5. Create responsive data tables with sorting/filtering
   2. Backend (Java):
      1. Create REST API endpoints for market analysis
      2. Generate aggregate statistics from the housing dataset
      3. Integrate with the ML model container from Task 1
      4. Implement caching for performance optimisation

### Technical Requirements

1. Next.js Implementation:
   1. Use App Router
   2. Implement server components and client components appropriately
   3. Use React Server Components for initial data loading
   4. Implement proper data fetching strategies
   5. Create custom hooks for shared functionality
2. UI/UX Requirements:
   1. Create responsive layouts using Tailwind CSS
   2. Implement accessible UI components following WCAG guidelines
   3. Use appropriate loading states and error boundaries
   4. Create smooth transitions between pages and application states
   5. Design a cohesive UI that follows modern design principles
3. State Management & Data Flow:
   1. Implement appropriate client-side state management
   2. Handle form state effectively with validation
   3. Create efficient data fetching patterns
   4. Properly manage API communication and error states
4. Code Quality & Organisation:
   1. Structure the codebase following Next.js best practices

### Technical Constraints

- Python 3.12+
- FastAPI
- Java 21
- Spring Boot 3.4.4

### Deliverables

1. Source code in github
2. Ability to show this live during interview
