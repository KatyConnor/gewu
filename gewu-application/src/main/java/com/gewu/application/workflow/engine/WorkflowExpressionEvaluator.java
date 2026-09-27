package com.gewu.application.workflow.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流表达式求值器（51 号 §五）。
 * <p>自研递归下降解析器：变量路径（{@code vars.amount} / {@code n1.status}）、
 * 字面量（数字/单双引号字符串/true/false/null）、比较（== != >= <= > <）、
 * 逻辑（and or not / && || !）、括号、白名单函数（isEmpty/contains/size/now）。
 * <p>安全红线：不接入任何脚本语言（SpEL/OGNL/JS）；表达式长度上限 1024；
 * 仅读变量不执行方法。语法错误抛 {@link IllegalArgumentException}（WV-07 语法闸）。
 *
 * <p>文法：
 * <pre>
 * expr  := or
 * or    := and (('||' | 'or') and)*
 * and   := not (('&&' | 'and') not)*
 * not   := ('!' | 'not') not | cmp
 * cmp   := primary (('=='|'!='|'>='|'<='|'>'|'<') primary)?
 * primary := '(' expr ')' | func | literal | varPath
 * func  := isEmpty(x) | contains(a,b) | size(x) | now()
 * </pre>
 *
 * @since 1.0.0
 */
@Component
public class WorkflowExpressionEvaluator {

    /** 表达式长度上限（防超长注入面） */
    public static final int MAX_EXPRESSION_LENGTH = 1024;

    /** 白名单函数表（仅函数名，无方法调用） */
    private static final Set<String> FUNCTIONS = Set.of("isEmpty", "contains", "size", "now");

    /** 求值表达式（变量空间只读；语法错误抛 IllegalArgumentException） */
    public Object evaluate(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return Boolean.TRUE;
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("表达式超长（上限 " + MAX_EXPRESSION_LENGTH + " 字符）");
        }
        Parser parser = new Parser(expression.trim(), variables == null ? Map.of() : variables);
        Object result = parser.parseExpression();
        parser.expectEnd();
        return result;
    }

    /** 求值为布尔（非布尔结果按真值约定：null/false/空串/0 为假） */
    public boolean evaluateBoolean(String expression, Map<String, Object> variables) {
        Object result = evaluate(expression, variables);
        if (result instanceof Boolean b) {
            return b;
        }
        if (result == null) {
            return false;
        }
        if (result instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        String text = String.valueOf(result);
        return !text.isBlank() && !"false".equalsIgnoreCase(text);
    }

    /** 语法校验（WV-07 语法闸；只解析不求值——变量路径不校验存在性） */
    public void validateSyntax(String expression) {
        evaluate(expression, Map.of());
    }

    // ==================== 词法 ====================

    private enum TokenType { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, COMMA, DOT, END }

    private record Token(TokenType type, String text) {
    }

    private static class Lexer {
        private final String src;
        private int pos;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }
                if (Character.isDigit(c) || c == '-' && isNumberStart(tokens)) {
                    int start = pos;
                    if (c == '-') {
                        pos++;
                    }
                    while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                        pos++;
                    }
                    tokens.add(new Token(TokenType.NUMBER, src.substring(start, pos)));
                    continue;
                }
                if (c == '\'' || c == '"') {
                    int start = pos;
                    pos++;
                    while (pos < src.length() && src.charAt(pos) != c) {
                        pos++;
                    }
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("字符串字面量未闭合: " + src.substring(start));
                    }
                    pos++;
                    tokens.add(new Token(TokenType.STRING, src.substring(start + 1, pos - 1)));
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = pos;
                    while (pos < src.length()
                            && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                        pos++;
                    }
                    String word = src.substring(start, pos);
                    // and/or/not 关键字归一为操作符
                    if (word.equalsIgnoreCase("and")) {
                        tokens.add(new Token(TokenType.OP, "&&"));
                    } else if (word.equalsIgnoreCase("or")) {
                        tokens.add(new Token(TokenType.OP, "||"));
                    } else if (word.equalsIgnoreCase("not")) {
                        tokens.add(new Token(TokenType.OP, "!"));
                    } else if (word.equalsIgnoreCase("true") || word.equalsIgnoreCase("false")) {
                        tokens.add(new Token(TokenType.IDENT, word.toLowerCase()));
                    } else {
                        tokens.add(new Token(TokenType.IDENT, word));
                    }
                    continue;
                }
                if (c == '(') {
                    tokens.add(new Token(TokenType.LPAREN, "("));
                    pos++;
                    continue;
                }
                if (c == ')') {
                    tokens.add(new Token(TokenType.RPAREN, ")"));
                    pos++;
                    continue;
                }
                if (c == ',') {
                    tokens.add(new Token(TokenType.COMMA, ","));
                    pos++;
                    continue;
                }
                if (c == '.') {
                    tokens.add(new Token(TokenType.DOT, "."));
                    pos++;
                    continue;
                }
                String two = pos + 1 < src.length() ? src.substring(pos, pos + 2) : "";
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=")
                        || two.equals("&&") || two.equals("||")) {
                    tokens.add(new Token(TokenType.OP, two));
                    pos += 2;
                    continue;
                }
                if (c == '>' || c == '<' || c == '!' || c == '+') {
                    tokens.add(new Token(TokenType.OP, String.valueOf(c)));
                    pos++;
                    continue;
                }
                throw new IllegalArgumentException("表达式含非法字符: '" + c + "'");
            }
            tokens.add(new Token(TokenType.END, ""));
            return tokens;
        }

        private boolean isNumberStart(List<Token> tokens) {
            // 负号仅出现在表达式开头或操作符/左括号/逗号后
            if (tokens.isEmpty()) {
                return true;
            }
            TokenType last = tokens.get(tokens.size() - 1).type();
            return last == TokenType.OP || last == TokenType.LPAREN || last == TokenType.COMMA;
        }
    }

    // ==================== 递归下降解析与求值 ====================

    private static class Parser {
        private final List<Token> tokens;
        private final Map<String, Object> variables;
        private int index;

        Parser(String src, Map<String, Object> variables) {
            this.tokens = new Lexer(src).tokenize();
            this.variables = variables;
        }

        Object parseExpression() {
            return parseOr();
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (matchOp("||")) {
                Object right = parseAnd();
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseNot();
            while (matchOp("&&")) {
                Object right = parseNot();
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        private Object parseNot() {
            if (matchOp("!")) {
                return !truthy(parseNot());
            }
            return parseCmp();
        }

        private Object parseCmp() {
            Object left = parsePrimary();
            String op = peekOp();
            if (op != null && Set.of("==", "!=", ">=", "<=", ">", "<").contains(op)) {
                next();
                Object right = parsePrimary();
                return compare(left, right, op);
            }
            return left;
        }

        private Object parsePrimary() {
            Token token = peek();
            switch (token.type()) {
                case LPAREN -> {
                    next();
                    Object value = parseExpression();
                    expect(TokenType.RPAREN, ")");
                    return value;
                }
                case NUMBER -> {
                    next();
                    String text = token.text();
                    return text.contains(".") ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
                }
                case STRING -> {
                    next();
                    return token.text();
                }
                case IDENT -> {
                    next();
                    String word = token.text();
                    if (FUNCTIONS.contains(word)) {
                        return parseFunction(word);
                    }
                    if (word.equals("true")) {
                        return Boolean.TRUE;
                    }
                    if (word.equals("false")) {
                        return Boolean.FALSE;
                    }
                    if (word.equals("null")) {
                        return null;
                    }
                    return resolvePath(word);
                }
                default -> throw new IllegalArgumentException("表达式语法错误: 意外的符号 '" + token.text() + "'");
            }
        }

        private Object parseFunction(String name) {
            expect(TokenType.LPAREN, "(");
            List<Object> args = new ArrayList<>();
            if (peek().type() != TokenType.RPAREN) {
                args.add(parseExpression());
                while (matchComma()) {
                    args.add(parseExpression());
                }
            }
            expect(TokenType.RPAREN, ")");
            switch (name) {
                case "isEmpty":
                    requireArgs(name, args, 1);
                    Object arg = args.get(0);
                    return arg == null || String.valueOf(arg).isBlank() || arg instanceof List l && l.isEmpty()
                            || arg instanceof Map m && m.isEmpty();
                case "contains":
                    requireArgs(name, args, 2);
                    return String.valueOf(args.get(0) == null ? "" : args.get(0))
                            .contains(String.valueOf(args.get(1) == null ? "" : args.get(1)));
                case "size":
                    requireArgs(name, args, 1);
                    Object target = args.get(0);
                    if (target instanceof List l) {
                        return l.size();
                    }
                    if (target instanceof Map m) {
                        return m.size();
                    }
                    return target == null ? 0 : String.valueOf(target).length();
                case "now":
                    return System.currentTimeMillis();
                default:
                    throw new IllegalArgumentException("非白名单函数: " + name);
            }
        }

        /** 变量路径解析：a.b.c 逐级下钻（Map 取键、List 取数字段下标）；
         *  不可下钻时消费剩余路径段后返回 null（保证语法完整性，WV-07 校验兼容未知变量） */
        private Object resolvePath(String root) {
            Object current = variables.get(root);
            while (peek().type() == TokenType.DOT) {
                next();
                Token key = peek().type() == TokenType.NUMBER
                        ? next()
                        : expect(TokenType.IDENT, "变量路径段");
                if (current instanceof Map<?, ?> map) {
                    current = map.get(key.text());
                } else if (current instanceof List<?> list) {
                    try {
                        current = list.get(Integer.parseInt(key.text()));
                    } catch (NumberFormatException | IndexOutOfBoundsException e) {
                        current = null;
                    }
                } else {
                    current = null; // 不可下钻：继续消费剩余路径段保持语法位置正确
                }
            }
            return current;
        }

        private Object compare(Object left, Object right, String op) {
            if (left instanceof Number ln && right instanceof Number rn) {
                double a = ln.doubleValue();
                double b = rn.doubleValue();
                return switch (op) {
                    case "==" -> a == b;
                    case "!=" -> a != b;
                    case ">" -> a > b;
                    case "<" -> a < b;
                    case ">=" -> a >= b;
                    case "<=" -> a <= b;
                    default -> false;
                };
            }
            String a = left == null ? null : String.valueOf(left);
            String b = right == null ? null : String.valueOf(right);
            return switch (op) {
                case "==" -> java.util.Objects.equals(a, b);
                case "!=" -> !java.util.Objects.equals(a, b);
                case ">" -> compareText(a, b) > 0;
                case "<" -> compareText(a, b) < 0;
                case ">=" -> compareText(a, b) >= 0;
                case "<=" -> compareText(a, b) <= 0;
                default -> false;
            };
        }

        private int compareText(String a, String b) {
            if (a == null || b == null) {
                return a == null && b == null ? 0 : (a == null ? -1 : 1);
            }
            try {
                return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        }

        private boolean truthy(Object value) {
            if (value instanceof Boolean b) {
                return b;
            }
            if (value == null) {
                return false;
            }
            if (value instanceof Number n) {
                return n.doubleValue() != 0d;
            }
            return !String.valueOf(value).isBlank();
        }

        // ---------- token 辅助 ----------
        private Token peek() {
            return tokens.get(index);
        }

        private Token next() {
            return tokens.get(index++);
        }

        private boolean matchOp(String op) {
            Token token = peek();
            if (token.type() == TokenType.OP && token.text().equals(op)) {
                index++;
                return true;
            }
            return false;
        }

        private boolean matchComma() {
            Token token = peek();
            if (token.type() == TokenType.COMMA) {
                index++;
                return true;
            }
            return false;
        }

        private String peekOp() {
            Token token = peek();
            return token.type() == TokenType.OP ? token.text() : null;
        }

        private Token expect(TokenType type, String what) {
            Token token = peek();
            if (token.type() != type) {
                throw new IllegalArgumentException("表达式语法错误: 期望 " + what + " 实际 '" + token.text() + "'");
            }
            return next();
        }

        private void expectEnd() {
            if (peek().type() != TokenType.END) {
                throw new IllegalArgumentException("表达式语法错误: 末尾多余符号 '" + peek().text() + "'");
            }
        }

        private void requireArgs(String name, List<Object> args, int count) {
            if (args.size() != count) {
                throw new IllegalArgumentException("函数 " + name + " 需要 " + count + " 个参数");
            }
        }
    }
}
