package org.meter.osp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author meter
 * @date 2018/11/11
 * @desc 中文域名-punycode相互转换
 *		支持中文加数字及字母的格式
 */
public class JPunycode {
    /* Punycode parameters */
    private final static int TMIN = 1;
    private final static int TMAX = 26;
    private final static int BASE = 36;
    private final static int INITIAL_N = 128;
    private final static int INITIAL_BIAS = 72;
    private final static int DAMP = 700;
    private final static int SKEW = 38;
    private final static char DELIMITER = '-';
    private final static Logger logger = LoggerFactory.getLogger(JPunycode.class);

    /**
     * @param input Unicode string like 中文百度
     * @return Punycoded string like fiq841b68em2s
     * _test: domain-->punycode
     * domain:www.中文百度.com.cn
     * pyconde:www.xn--fiq841b68em2s.com.cn
     * @desc Punycodes a unicode string.
     */
    private static String format(String input)
            throws PunycodeException {
        int n = INITIAL_N;
        int delta = 0;
        int bias = INITIAL_BIAS;
        StringBuilder output = new StringBuilder();

        // Copy all basic code points to the output
        int b = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (isBasic(c)) {
                output.append(c);
                b++;
            }
        }

        // Append delimiter
        if (b > 0) {
            output.append(DELIMITER);
        }

        int h = b;
        while (h < input.length()) {
            int m = Integer.MAX_VALUE;

            // Find the minimum code point >= n
            for (int i = 0; i < input.length(); i++) {
                int c = input.charAt(i);
                if (c >= n && c < m) {
                    m = c;
                }
            }

            if (m - n > (Integer.MAX_VALUE - delta) / (h + 1)) {
                throw new PunycodeException(PunycodeException.OVERFLOW);
            }
            delta = delta + (m - n) * (h + 1);
            n = m;

            for (int j = 0; j < input.length(); j++) {
                int c = input.charAt(j);
                if (c < n) {
                    delta++;
                    if (0 == delta) {
                        throw new PunycodeException(PunycodeException.OVERFLOW);
                    }
                }
                if (c == n) {
                    int q = delta;

                    for (int k = BASE; ; k += BASE) {
                        int t;
                        if (k <= bias) {
                            t = TMIN;
                        } else if (k >= bias + TMAX) {
                            t = TMAX;
                        } else {
                            t = k - bias;
                        }
                        if (q < t) {
                            break;
                        }
                        output.append((char) digit2codepoint(t + (q - t) % (BASE - t)));
                        q = (q - t) / (BASE - t);
                    }

                    output.append((char) digit2codepoint(q));
                    bias = adapt(delta, h + 1, h == b);
                    delta = 0;
                    h++;
                }
            }

            delta++;
            n++;
        }

        return output.toString();
    }

    /**
     * @param input Punycode string like fiq841b68em2s
     * @return Unicode cn string like 中文百度
     * _test: pyconde-->domain
     * domain:www.中文百度.com.cn
     * pyconde:www.xn--fiq841b68em2s.com.cn
     * @desc Decode a punycoded string.
     */
    private static String unFormat(String input)
            throws PunycodeException {
        int n = INITIAL_N;
        int i = 0;
        int bias = INITIAL_BIAS;
        StringBuilder output = new StringBuilder();

        int d = input.lastIndexOf(DELIMITER);
        if (d > 0) {
            for (int j = 0; j < d; j++) {
                char c = input.charAt(j);
                if (!isBasic(c)) {
                    throw new PunycodeException(PunycodeException.BAD_INPUT);
                }
                output.append(c);
            }
            d++;
        } else {
            d = 0;
        }

        while (d < input.length()) {
            int oldi = i;
            int w = 1;

            for (int k = BASE; ; k += BASE) {
                if (d == input.length()) {
                    throw new PunycodeException(PunycodeException.BAD_INPUT);
                }
                int c = input.charAt(d++);
                int digit = codepoint2digit(c);
                if (digit > (Integer.MAX_VALUE - i) / w) {
                    throw new PunycodeException(PunycodeException.OVERFLOW);
                }

                i = i + digit * w;

                int t;
                if (k <= bias) {
                    t = TMIN;
                } else if (k >= bias + TMAX) {
                    t = TMAX;
                } else {
                    t = k - bias;
                }
                if (digit < t) {
                    break;
                }
                w = w * (BASE - t);
            }

            bias = adapt(i - oldi, output.length() + 1, oldi == 0);

            if (i / (output.length() + 1) > Integer.MAX_VALUE - n) {
                throw new PunycodeException(PunycodeException.OVERFLOW);
            }

            n = n + i / (output.length() + 1);
            i = i % (output.length() + 1);
            output.insert(i, (char) n);
            i++;
        }

        return output.toString();
    }

    private final static int adapt(int delta, int numpoints, boolean first) {
        if (first) {
            delta = delta / DAMP;
        } else {
            delta = delta / 2;
        }

        delta = delta + (delta / numpoints);

        int k = 0;
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta = delta / (BASE - TMIN);
            k = k + BASE;
        }

        return k + ((BASE - TMIN + 1) * delta) / (delta + SKEW);
    }

    private final static boolean isBasic(char c) {
        return c < 0x80;
    }

    private final static int digit2codepoint(int d)
            throws PunycodeException {
        if (d < 26) {
            // 0..25 : 'a'..'z'
            return d + 'a';
        } else if (d < 36) {
            // 26..35 : '0'..'9';
            return d - 26 + '0';
        } else {
            throw new PunycodeException(PunycodeException.BAD_INPUT);
        }
    }

    private final static int codepoint2digit(int c)
            throws PunycodeException {
        if (c - '0' < 10) {
            // '0'..'9' : 26..35
            return c - '0' + 26;
        } else if (c - 'a' < 26) {
            // 'a'..'z' : 0..25
            return c - 'a';
        } else {
            throw new PunycodeException(PunycodeException.BAD_INPUT);
        }
    }

    /**
     * @param domain Input a cn domain like :www.百度中文abc123.com.cn
     * @return punycode like www.xn--abc123-9v7ip83i46m153b.com.cn
     * @author meter
     * @date 2018/11/11
     */
    public static String encodePunycode(String domain) {
        String result = "";
        try {
            if (domain.getBytes().length == domain.length()) {//全英文，无需转换
                return domain;
            }
            String d[] = toArray(domain);

            for (int i = 0; i < d.length; i++) {
                String sub = d[i];
                if (sub.getBytes().length == sub.length()) {
                    result += sub;
                } else {
                    result = result + "xn--" + format(sub);
                }
                result += ".";
            }
            result = result.endsWith(".") ? result.substring(0, result.length() - 1) : result;
            return result;
        } catch (Exception e) {
            logger.error(null, e);
            return result;
        }

    }

    /**
     * @desc Decode punycode to cn domain
     * @author meter
     * @date 2018/11/11
     * @param domain input a punycode like www.xn--abc123-9v7ip83i46m153b.com.cn
     * @return cn domain like www.百度中文abc123.com.cn
     */
    public static String decodePunycode(String domain) {
        String result = "";
        try {
            String d[] = toArray(domain);

            for (int i = 0; i < d.length; i++) {
                String sub = d[i];
                if (sub.startsWith("xn--")) {
                    result = result + unFormat(sub.substring(4));
                } else {
                    result += sub;
                }
                result += ".";
            }
            result = result.endsWith(".") ? result.substring(0, result.length() - 1) : result;
            return result;
        } catch (Exception e) {
            logger.error(null, e);
            return result;
        }

    }

    /**
     * @desc split to array with regex '.'
     * @author meter
     * @date 2018/11/11
     * @param domain
     * @return array
     */
    private static String[] toArray(String domain) {
        return domain.split("\\.");

    }
	class PunycodeException
	  extends Exception
	{
	  public static String OVERFLOW = "Overflow.";
	  public static String BAD_INPUT = "Bad input.";

	  /**
	   * Creates a new PunycodeException.
	   *
	   * @param m message.
	   */
	  public PunycodeException(String m)
	  {
	    super(m);
	  }
	}
    /**
     * @desc test this program
     * @author meter
     * @date 2018/11/11
     */
    public static void main(String[] args) throws Exception {

        String str = "www.百度中文abc123.com.cn";
        str = encodePunycode(str);
        logger.info("encodePunycode:" + str);
        str = decodePunycode(str);
        logger.info("decodePunycode:" + str);
    }

}
