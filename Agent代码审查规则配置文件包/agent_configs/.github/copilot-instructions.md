## Code Review Guidelines

When reviewing code, follow these rules strictly:

### Security (P0/P1 - Must Fix)
- Never hardcode secrets [L3-SEC-015]
- Use parameterized queries [L3-SEC-001]
- Sanitize all outputs [L3-SEC-023]
- CSRF protection on state changes [L3-SEC-028]
- Auth check on every endpoint [L3-SEC-034]
- Password storage with bcrypt/argon2 [L3-SEC-007]
- No sensitive data in logs [L3-SEC-017]
- TLS 1.2+ for transport [L3-SEC-016]

### Code Quality
- Functions < 50 lines [L1-LOGIC-004]
- Parameters < 5 [L1-LOGIC-003]
- Nesting < 3 levels [L1-LOGIC-001]
- No magic numbers [L1-LOGIC-009]
- Meaningful names [L1-NAME-001]
- No empty catch blocks [L1-ERR-001]
- No overly broad catches [L1-ERR-002]
- Resources released in finally/RAII [L1-ERR-004]

### Language-Specific
- Python: PEP 8, type hints required [L2-PY-*]
- Java: Google Style, try-with-resources [L2-JAVA-*]
- TypeScript: strict mode, no any [L2-JSTS-*]
- JavaScript: ES6+, async/await [L2-JSTS-*]
- Go: error handling, goroutine safety [L2-GO-*]
- Rust: ownership, no unsafe [L2-RUST-*]
- C/C++: RAII, no raw pointers [L2-CPP-*]
- C#: async/await, LINQ [L2-CS-*]

### Architecture
- Single Responsibility [L3-ARCH-001]
- No cross-layer calls [L3-ARCH-003]
- No circular dependencies [L3-ARCH-004]
- Pagination max limit [L3-ARCH-019]

### Performance
- No N+1 queries [L3-PERF-001]
- Reasonable timeouts [L3-PERF-010]
- Connection pool sizing [L3-PERF-009]

### Output Format
Sort by severity (P0 > P1 > P2 > P3):
1. [Level] Rule ID: Rule name
2. Location: file:line
3. Issue: description
4. Suggestion: fix with code example
