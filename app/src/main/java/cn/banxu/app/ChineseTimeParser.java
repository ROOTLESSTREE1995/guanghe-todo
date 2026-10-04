package cn.banxu.app;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves one extracted time phrase, always relative to the notification's receipt time. */
public final class ChineseTimeParser {
    private ChineseTimeParser() {}

    public static final class Result {
        public final long dueAt;
        public final String precision;
        public final String note;
        private Result(long dueAt, String precision, String note) {
            this.dueAt = dueAt;
            this.precision = precision;
            this.note = note;
        }
    }

    private static final String N = "[0-9零〇一二两三四五六七八九十]+";
    private static final Pattern RELATIVE = Pattern.compile("^(" + N + ")(小时|分钟|天)后$");
    private static final Pattern FRACTIONAL_HOURS = Pattern.compile("^(半|" + N + "个半|[0-9]+\\.[0-9]+)小时后$");
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(.*)$");
    private static final Pattern CN_DATE = Pattern.compile("^(?:(" + N + ")年)?(" + N + ")月(" + N + ")[日号](.*)$");
    private static final Pattern WEEK = Pattern.compile("^(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天1-7])(.*)$");
    private static final Pattern WEEKEND = Pattern.compile("^(下下|下|本|这)?周末(.*)$");
    private static final Pattern MONTH_END = Pattern.compile("^(下下|下|本|这)?月底(.*)$");
    private static final Pattern COLON_TIME = Pattern.compile("^(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?$");
    private static final Pattern CHINESE_TIME = Pattern.compile("^(" + N + ")[点时](?:(半|一刻|三刻)|(" + N + ")分?)?(?:整)?$");
    private static final String[] PERIODS = {"凌晨", "早上", "上午", "早晨", "清晨", "中午", "下午", "傍晚", "晚上", "夜里", "夜间"};

    public static Result resolve(String phrase, long receivedAt, ZoneId zone) {
        if (phrase == null || phrase.trim().isEmpty() || zone == null || receivedAt <= 0) {
            return unknown("消息未提供可确定的时间");
        }
        try {
            return parse(phrase, Instant.ofEpochMilli(receivedAt).atZone(zone));
        } catch (DateTimeException | ArithmeticException | IllegalArgumentException ignored) {
            return unknown("时间或日期无效，未设置提醒");
        }
    }

    private static Result parse(String phrase, ZonedDateTime received) {
        String text = phrase.trim().replaceAll("[\\s\\u3000]+", "").replace('：', ':')
                .replaceAll("^[，,。.!！?？;；:]+|[，,。.!！?？;；:]+$", "");
        text = text.replaceFirst("^(?:截止到|截止至|截至|截止|于)", "");
        boolean before = text.matches(".*(?:之前|以前|前)$");
        text = text.replaceFirst("(?:之前|以前|前)$", "");
        boolean approximate = text.matches(".*(?:左右|许)$");
        text = text.replaceFirst("(?:左右|许)$", "");
        // Do not choose an endpoint of a range, an alternative, or a school-schedule expression.
        if (text.matches(".*(?:到|至|~|～|—|–|、|，|,|或|和|放学|下课|尽快|有空|课间).*")) {
            return unknown("时间包含范围、多个选择或未说明的作息，未设置提醒");
        }
        text = text.replaceFirst("^明早", "明天早上").replaceFirst("^明晚", "明天晚上")
                .replaceFirst("^明晨", "明天早晨").replaceFirst("^今早", "今天早上")
                .replaceFirst("^今晨", "今天早晨").replaceFirst("^今晚", "今天晚上")
                .replaceFirst("^昨晚", "昨天晚上").replaceFirst("^昨早", "昨天早上");

        Matcher fractional = FRACTIONAL_HOURS.matcher(text);
        if (fractional.matches()) {
            String amountText = fractional.group(1);
            BigDecimal amount;
            if ("半".equals(amountText)) amount = new BigDecimal("0.5");
            else if (amountText.endsWith("个半")) {
                int whole = number(amountText.substring(0, amountText.length() - 2));
                if (whole < 0) return unknown("相对时间无效");
                amount = BigDecimal.valueOf(whole).add(new BigDecimal("0.5"));
            } else amount = new BigDecimal(amountText);
            if (amount.compareTo(BigDecimal.valueOf(3660)) > 0) return unknown("相对时间无效");
            long millis = amount.multiply(BigDecimal.valueOf(3_600_000L)).longValueExact();
            return new Result(Math.addExact(received.toInstant().toEpochMilli(), millis), approximate ? "estimated" : "exact",
                    "按消息收到时间推算" + amount.toPlainString() + "小时后" + (approximate ? "；原文为大约时间" : ""));
        }
        Matcher relative = RELATIVE.matcher(text);
        if (relative.matches()) {
            int amount = number(relative.group(1));
            if (amount < 0 || amount > 3660) return unknown("相对时间无效");
            String unit = relative.group(2);
            String note = "按消息收到时间推算" + amount + unit + "后" + (approximate ? "；原文为大约时间" : "");
            if ("天".equals(unit)) {
                return atLocal(received.toLocalDate().plusDays(amount), received.toLocalTime(), received.getZone(),
                        approximate ? "estimated" : "exact", note);
            }
            ZonedDateTime result = "小时".equals(unit) ? received.plusHours(amount) : received.plusMinutes(amount);
            return new Result(result.toInstant().toEpochMilli(), approximate ? "estimated" : "exact",
                    note);
        }

        LocalDate today = received.toLocalDate();
        LocalDate date = today;
        boolean hasDate = false;
        boolean estimatedDate = false;
        String dateNote = "";
        Matcher iso = ISO_DATE.matcher(text);
        Matcher cn = CN_DATE.matcher(text);
        Matcher week = WEEK.matcher(text);
        Matcher weekend = WEEKEND.matcher(text);
        Matcher monthEnd = MONTH_END.matcher(text);
        if (iso.matches()) {
            date = LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            text = iso.group(4);
            if (text.startsWith("T")) text = text.substring(1);
            hasDate = true;
        } else if (cn.matches()) {
            int month = number(cn.group(2)), day = number(cn.group(3));
            if (cn.group(1) != null) date = LocalDate.of(number(cn.group(1)), month, day);
            else {
                date = nearestAnnualDate(today, month, day);
                dateNote = "；原文无年份，按与消息收到日期最近的相邻年份日期推算为" + date;
            }
            text = cn.group(4);
            hasDate = true;
        } else if (weekend.matches()) {
            int weeks = "下下".equals(weekend.group(1)) ? 2 : "下".equals(weekend.group(1)) ? 1 : 0;
            date = today.plusDays(7 - today.getDayOfWeek().getValue() + weeks * 7L);
            text = weekend.group(2);
            hasDate = true;
            estimatedDate = true;
            dateNote = "；原文只说明周末，暂用对应周日（" + date + "）提醒";
        } else if (monthEnd.matches()) {
            int months = "下下".equals(monthEnd.group(1)) ? 2 : "下".equals(monthEnd.group(1)) ? 1 : 0;
            date = today.plusMonths(months);
            date = date.withDayOfMonth(date.lengthOfMonth());
            text = monthEnd.group(2);
            hasDate = true;
            estimatedDate = true;
            dateNote = "；原文只说明月底，暂用对应月份最后一天（" + date + "）提醒";
        } else if (week.matches()) {
            String prefix = week.group(1);
            int weekday = weekday(week.group(2));
            int delta = weekday - today.getDayOfWeek().getValue();
            if ("下".equals(prefix)) delta += 7;
            else if ("下下".equals(prefix)) delta += 14;
            else if (prefix == null && delta < 0) delta += 7;
            date = today.plusDays(delta);
            text = week.group(3);
            hasDate = true;
        } else {
            String[] dates = {"大后天", "今天", "今日", "明天", "明日", "后天", "昨天", "昨日", "前天"};
            int[] offsets = {3, 0, 0, 1, 1, 2, -1, -1, -2};
            for (int i = 0; i < dates.length; i++) {
                if (text.startsWith(dates[i])) {
                    date = today.plusDays(offsets[i]);
                    text = text.substring(dates[i].length());
                    hasDate = true;
                    break;
                }
            }
        }

        String period = "";
        for (String candidate : PERIODS) {
            if (text.startsWith(candidate)) {
                period = candidate;
                text = text.substring(candidate.length());
                break;
            }
        }
        if (text.isEmpty()) {
            if (!hasDate && period.isEmpty()) return unknown("消息未提供可确定的时间");
            if (hasDate && period.isEmpty() && before && !approximate && !estimatedDate) {
                return atLocal(date, LocalTime.MIDNIGHT, received.getZone(), "exact", "“日期之前”按该日期开始的00:00作为期限" + dateNote);
            }
            int hour = defaultHour(period);
            String note = period.isEmpty() ? "只说明日期，暂用当天18:00作为提醒时间，并非原文确定的期限"
                    : "只说明“" + period + "”，暂用" + String.format(java.util.Locale.ROOT, "%02d:00", hour) + "作为提醒时间，并非原文确定的期限";
            return atLocal(date, LocalTime.of(hour, 0), received.getZone(), "estimated", note + dateNote);
        }

        int hour, minute = 0, second = 0;
        Matcher colon = COLON_TIME.matcher(text);
        Matcher chinese = CHINESE_TIME.matcher(text);
        if (colon.matches()) {
            hour = Integer.parseInt(colon.group(1));
            minute = Integer.parseInt(colon.group(2));
            if (colon.group(3) != null) second = Integer.parseInt(colon.group(3));
        } else if (chinese.matches()) {
            hour = number(chinese.group(1));
            if ("半".equals(chinese.group(2))) minute = 30;
            else if ("一刻".equals(chinese.group(2))) minute = 15;
            else if ("三刻".equals(chinese.group(2))) minute = 45;
            else if (chinese.group(3) != null) minute = number(chinese.group(3));
        } else {
            return unknown("未能确定唯一时间，未设置提醒");
        }
        // Validate before converting a 12-hour clock; 24:70 must never be normalized.
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
            return unknown("时间无效，未设置提醒");
        }
        if ("下午".equals(period) || "傍晚".equals(period) || "晚上".equals(period)
                || "夜里".equals(period) || "夜间".equals(period)) {
            if (hour == 0) return unknown("时段和钟点不一致");
            if (hour < 12) hour += 12;
            // “晚上12点” conventionally means midnight at the end of that date.
            if (hour == 12 && !"下午".equals(period) && !"傍晚".equals(period)) {
                hour = 0;
                date = date.plusDays(1);
            }
            if ("下午".equals(period) && (hour < 12 || hour > 18)) return unknown("时段和钟点不一致");
            if ("傍晚".equals(period) && (hour < 17 || hour > 19)) return unknown("时段和钟点不一致");
            if (!"下午".equals(period) && !"傍晚".equals(period) && hour != 0 && hour < 18) {
                return unknown("夜间钟点存在歧义，未设置提醒");
            }
        } else if ("中午".equals(period)) {
            if (hour == 1 || hour == 2) hour += 12;
            if (hour < 11 || hour > 14) return unknown("时段和钟点不一致");
        } else if (!period.isEmpty()) {
            if ("凌晨".equals(period) && hour == 12) hour = 0;
            if (hour > 12 || ("凌晨".equals(period) && hour > 6)) return unknown("时段和钟点不一致");
        }
        return atLocal(date, LocalTime.of(hour, minute, second), received.getZone(), approximate || estimatedDate ? "estimated" : "exact",
                "按消息收到日期和时区推算" + (approximate ? "；原文为大约时间，暂用此时刻提醒" : "") + dateNote);
    }

    private static LocalDate nearestAnnualDate(LocalDate today, int month, int day) {
        // The leap year permits validating February 29 without making February 30 valid.
        LocalDate.of(2000, month, day);
        LocalDate nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        // An omitted year does not justify moving yesterday's deadline a whole year ahead.
        // Consider only neighboring years, choosing the future date if distances are equal.
        for (int year = today.getYear() - 1; year <= today.getYear() + 1; year++) {
            try {
                LocalDate date = LocalDate.of(year, month, day);
                long distance = Math.abs(date.toEpochDay() - today.toEpochDay());
                if (distance < nearestDistance || (distance == nearestDistance && (nearest == null || date.isAfter(nearest)))) {
                    nearest = date;
                    nearestDistance = distance;
                }
            } catch (DateTimeException ignored) { /* February 29 may have no nearby valid year. */ }
        }
        if (nearest != null) return nearest;
        throw new IllegalArgumentException("没有可用日期");
    }

    private static int defaultHour(String period) {
        if (period.isEmpty() || "傍晚".equals(period)) return 18;
        if ("凌晨".equals(period)) return 3;
        if ("中午".equals(period)) return 12;
        if ("下午".equals(period)) return 15;
        if ("晚上".equals(period) || "夜里".equals(period) || "夜间".equals(period)) return 20;
        return 9;
    }

    private static Result atLocal(LocalDate date, LocalTime time, ZoneId zone, String precision, String note) {
        LocalDateTime local = LocalDateTime.of(date, time);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) return unknown("该时区在此时间发生夏令时切换，未设置提醒");
        long epoch = local.toInstant(offsets.get(0)).toEpochMilli();
        if (epoch <= 0) return unknown("日期超出支持范围");
        return new Result(epoch, precision, note);
    }

    private static int weekday(String value) {
        return "日".equals(value) || "天".equals(value) ? 7 : number(value);
    }

    private static int number(String text) {
        if (text == null || text.isEmpty()) return -1;
        if (text.matches("[0-9]+")) return Integer.parseInt(text);
        if (text.matches(".*[0-9].*")) return -1;
        if (text.contains("十")) {
            if (!text.matches("[一二两三四五六七八九]?十[一二三四五六七八九]?")) return -1;
            int pos = text.indexOf('十');
            int tens = pos == 0 ? 1 : digit(text.charAt(0));
            return tens * 10 + (pos == text.length() - 1 ? 0 : digit(text.charAt(pos + 1)));
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = digit(text.charAt(i));
            if (digit < 0) return -1;
            value = Math.addExact(Math.multiplyExact(value, 10), digit);
        }
        return value;
    }

    private static int digit(char c) {
        if (c == '零' || c == '〇') return 0;
        if (c == '两') return 2;
        int index = "一二三四五六七八九".indexOf(c);
        return index < 0 ? -1 : index + 1;
    }

    private static Result unknown(String note) { return new Result(0, "unknown", note); }
}
