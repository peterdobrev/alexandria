# Alexandria — Claude Code Guidelines

## Running Tests

```bash
JAVA_HOME=/Users/I765724/Library/Java/JavaVirtualMachines/sapmachine-25.0.3/Contents/Home \
  ./mvnw clean test
```

---

## General Code Practices

### Spring & Architecture

- Make a class a Spring bean only if it is reused or central to a service
- Prefer `@Bean` in `@Configuration` classes over `@Service` / `@Component` on the class itself
- Never use field injection (`@Autowired` on fields) — use constructor injection only (Lombok `@RequiredArgsConstructor`)

### Code Style

- DTOs must be classes (records are fine for immutable value types; use full classes when mutable state or validation is needed)
- Use standard Lombok annotations to reduce boilerplate (`@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Builder`, etc.)
- Never use `Pair` / `Triple` — create a named record instead
- Names must be clear and descriptive; no abbreviations unless widely recognised
- Code should be self-explanatory through naming; add a comment only when the logic is not immediately obvious — never comment what the code already says
- Static imports only when the method's purpose is clear from its name alone
  - ✅ `format("Error '%s'", msg)`, `base64Encode(bytes)`, `EMAIL_REGEX`
  - ❌ `of("a", "b")`, `MAX_VALUE`
- Split long methods into small, focused methods with descriptive names (no god methods)
- Stick to existing naming conventions in the codebase
- Prefer readability over premature optimisation
- Extract self-contained repeated logic into shared methods or classes

### API Design

- Make APIs strict — fail fast rather than being fault-tolerant
- Do not use `Optional` as a method parameter type
- Methods that can return `null` must return `Optional<T>` instead
- Group related parameters into a named record when a method has too many parameters
- Use `@Builder` (Lombok) for classes with many fields

### Configuration

- Never hardcode values in `application.properties` that should be environment-configured
  - ✅ `timeout=${app.timeout:30}`
  - ❌ `timeout=30`

---

## Exception Handling

- Do not create or use checked exceptions
- Custom exceptions extend the appropriate base exception with a clear message
- Do not propagate third-party library exceptions out of our code — wrap them
- Always propagate exceptions; if an exception must be swallowed, log it
- Write `throws` declarations on interfaces, not on implementation classes

---

## Unit Test Standards

### Naming

- Use `given_when_then` (or `when_then`) pattern for all test method names

### Structure

- Tests should be simple and readable, even at the cost of some duplication
- No conditional logic or loops inside tests

### Mocking

- Do not mock DTOs or plain data holders — construct them directly
- When setting up stubs use `any(...)` matchers; when **verifying** calls use the exact expected values (produces descriptive failures)
- Do not test another class's logic from within the current test class
- When the class under test delegates to a static utility, only cover the branches relevant to the current class

### Scope

- DTOs with custom serialisation/deserialisation should have tests for that behaviour
- Do not write tests that only assert an annotation is present on a field or method
