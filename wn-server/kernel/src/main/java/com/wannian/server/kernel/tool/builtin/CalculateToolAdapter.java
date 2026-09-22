package com.wannian.server.kernel.tool.builtin;

import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.tool.ToolAdapter;
import com.wannian.server.kernel.tool.ToolAdapterRequest;
import com.wannian.server.kernel.tool.ToolAdapterResult;
import com.wannian.server.kernel.tool.ToolJson;
import java.util.LinkedHashMap;
import java.util.Map;

/** 受限四则运算表达式计算器。 */
public final class CalculateToolAdapter implements ToolAdapter {

    @Override
    public ToolAdapterResult execute(ToolAdapterRequest request) {
        try {
            Map<String, String> fields = ToolJson.parseFlatObject(request.argumentsJson());
            String expression = ToolJson.requireString(fields, "expression").trim();
            double value = new Expr(expression).parse();
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("expression", expression);
            out.put("result", trimNumber(value));
            return new ToolAdapterResult.Succeeded(ToolJson.object(out));
        } catch (ArithmeticException | IllegalArgumentException ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.TOOL_INVALID_ARGUMENTS, ex.getMessage(), false);
        } catch (Exception ex) {
            return new ToolAdapterResult.Failed(
                    ErrorCodes.INTERNAL_DEFECT, "calculate 失败: " + ex.getMessage(), false);
        }
    }

    private static String trimNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new ArithmeticException("结果非有限数");
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** 递归下降：expr → term → factor。 */
    private static final class Expr {
        private final String s;
        private int i;

        Expr(String s) {
            this.s = s;
        }

        double parse() {
            double v = parseExpr();
            skip();
            if (i != s.length()) {
                throw new IllegalArgumentException("expression 未能完全解析");
            }
            return v;
        }

        private double parseExpr() {
            double v = parseTerm();
            while (true) {
                skip();
                if (match('+')) {
                    v += parseTerm();
                } else if (match('-')) {
                    v -= parseTerm();
                } else {
                    return v;
                }
            }
        }

        private double parseTerm() {
            double v = parseFactor();
            while (true) {
                skip();
                if (match('*')) {
                    v *= parseFactor();
                } else if (match('/')) {
                    double d = parseFactor();
                    if (d == 0.0) {
                        throw new ArithmeticException("除以零");
                    }
                    v /= d;
                } else {
                    return v;
                }
            }
        }

        private double parseFactor() {
            skip();
            if (match('+')) {
                return parseFactor();
            }
            if (match('-')) {
                return -parseFactor();
            }
            if (match('(')) {
                double v = parseExpr();
                skip();
                if (!match(')')) {
                    throw new IllegalArgumentException("缺少 ')'");
                }
                return v;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skip();
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("期望数字");
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean match(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }
    }
}
