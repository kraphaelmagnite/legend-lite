// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Postgres's reading of a date's or a timestamp's text, for a constant ({@code DATE 'January 8, 1999'}, {@code
 * TIMESTAMP '1/8/1999 4:05 PM'}): datetime.c's ParseDateTime and DecodeDateTime (Postgres 16), under Postgres's
 * default DateStyle (ISO, MDY), ported field by field -- a date's fields in their written orders, month and day names,
 * two-digit years, a year's day ({@code 1999.008}), a Julian day ({@code J2451187}), run-together digits ({@code
 * 19990108}), AM and PM, {@code epoch}. Text Postgres rejects is Postgres's error. What Postgres reads but this does
 * not is refused as not supported: a time zone's name, BC, {@code infinity}, the current time's words, a year beyond
 * 1 to 9999. A numeric time zone is read and, as Postgres does for a date and a timestamp without time zone, ignored.
 */
final class PostgresDateTime {

    private PostgresDateTime() {
    }

    /** A date and the microseconds after its midnight, up to a day's (24:00:00 is the next midnight). */
    record Value(LocalDate date, long micros) {
    }

    // the field types of datetime.h: a field's bit in a mask is 1 << its type
    private static final int RESERV = 0;
    private static final int MONTH = 1;
    private static final int YEAR = 2;
    private static final int DAY = 3;
    private static final int TZ = 5;
    private static final int IGNORE = 8;
    private static final int AMPM = 9;
    private static final int HOUR = 10;
    private static final int MINUTE = 11;
    private static final int SECOND = 12;
    private static final int DOY = 15;
    private static final int DOW = 16;
    private static final int UNITS = 17;
    private static final int ADBC = 18;
    private static final int ISOTIME = 23;
    private static final int DTZMOD = 28;

    private static final int DATE_M = (1 << YEAR) | (1 << MONTH) | (1 << DAY);
    private static final int TIME_M = (1 << HOUR) | (1 << MINUTE) | (1 << SECOND);

    // a UNITS word's value: the Julian day's ('j') and the others' (which no field here follows)
    private static final int JULIAN = 1;
    private static final int OTHER_UNIT = 2;
    // a RESERV word's value
    private static final int EPOCH = 1;
    private static final int OTHER_RESERVED = 2;
    private static final int AM = 0;
    private static final int PM = 1;
    private static final int BC = 1;

    /** datetime.c's datetktbl: the words of a date's text, with their types and values (a time zone's name, which
     *  Postgres looks up first, is not among them). */
    private static final Map<String, int[]> WORDS = words();

    private static Map<String, int[]> words() {
        Map<String, int[]> m = new LinkedHashMap<>();
        String[][] months = {{"jan", "january"}, {"feb", "february"}, {"mar", "march"}, {"apr", "april"},
                {"may"}, {"jun", "june"}, {"jul", "july"}, {"aug", "august"}, {"sep", "sept", "september"},
                {"oct", "october"}, {"nov", "november"}, {"dec", "december"}};
        for (int i = 0; i < months.length; i++) {
            for (String name : months[i]) {
                m.put(name, new int[] {MONTH, i + 1});
            }
        }
        String[][] days = {{"sun", "sunday"}, {"mon", "monday"}, {"tue", "tues", "tuesday"},
                {"wed", "weds", "wednesday"}, {"thu", "thur", "thurs", "thursday"}, {"fri", "friday"},
                {"sat", "saturday"}};
        for (int i = 0; i < days.length; i++) {
            for (String name : days[i]) {
                m.put(name, new int[] {DOW, i});
            }
        }
        m.put("am", new int[] {AMPM, AM});
        m.put("pm", new int[] {AMPM, PM});
        m.put("ad", new int[] {ADBC, 0});
        m.put("bc", new int[] {ADBC, BC});
        m.put("at", new int[] {IGNORE, 0});
        m.put("on", new int[] {IGNORE, 0});
        m.put("t", new int[] {ISOTIME, 0});
        m.put("epoch", new int[] {RESERV, EPOCH});
        for (String reserved : List.of("infinity", "+infinity", "-infinity", "now", "today", "tomorrow", "yesterday",
                "allballs")) {
            m.put(reserved, new int[] {RESERV, OTHER_RESERVED});
        }
        for (String julian : List.of("j", "jd", "julian")) {
            m.put(julian, new int[] {UNITS, JULIAN});
        }
        for (String unit : List.of("d", "dow", "doy", "h", "isodow", "isoyear", "m", "mm", "s", "y")) {
            m.put(unit, new int[] {UNITS, OTHER_UNIT});
        }
        m.put("dst", new int[] {DTZMOD, 0});
        return Collections.unmodifiableMap(m);
    }

    private static int bit(int type) {
        return 1 << type;
    }

    // ParseDateTime's field kinds
    private static final int NUMBER = 0;
    private static final int DATE = 1;
    private static final int TIME = 2;
    private static final int STRING = 3;
    private static final int ZONE = 4;
    private static final int SPECIAL = 5;

    private record Field(String text, int kind) {
    }

    /** Text Postgres rejects (its error), or reads otherwise than this does (refused). */
    private static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;
        final boolean invalid;
        final String reason;

        Bad(boolean invalid, String reason) {
            super(reason, null, false, false);
            this.invalid = invalid;
            this.reason = reason;
        }
    }

    /**
     * The text's value as Postgres's {@code date} or {@code timestamp} (its {@code type}) reads it: a date's time of
     * day is read and dropped, as date_in does.
     */
    static Value read(String text, String type, SqlAst.Pos pos) {
        try {
            if (text.length() > 128) {
                throw new Bad(false, "a " + type + " of more than 128 characters");
            }
            Value v = new Decoder(text, type).decode(fields(text, type));
            if (v.date().getYear() < 1000) {
                // Pure's constant of such a year reaches Postgres without its leading zeros (year 1 as 2001)
                throw new Bad(false, "a " + type + " before the year 1000");
            }
            if (v.date().getYear() > 9999) {
                throw new Bad(false, "a " + type + " outside the years 1 to 9999");
            }
            return v;
        } catch (Bad b) {
            throw b.invalid ? SqlTranslationException.invalid(b.reason, pos)
                    : SqlTranslationException.unsupported("'" + text + "' as a " + type + " is not supported yet ("
                            + b.reason + ")", pos);
        }
    }

    private static Bad badFormat(String type, String text) {
        return new Bad(true, "invalid input syntax for type " + type + ": \"" + text + "\"");
    }

    private static Bad overflow(String text) {
        return new Bad(true, "date/time field value out of range: \"" + text + "\"");
    }

    // ------------------------------------------------------------------ ParseDateTime

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean alpha(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean space(char c) {
        return c == ' ' || c >= '\t' && c <= '\r';
    }

    private static boolean punct(char c) {
        return c > ' ' && c < 127 && !digit(c) && !alpha(c);
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    /** ParseDateTime: the text's fields, each a run of digits and delimiters, of letters, or of a sign and either. */
    private static List<Field> fields(String s, String type) throws Bad {
        List<Field> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (space(c)) {
                i++;
                continue;
            }
            StringBuilder b = new StringBuilder();
            int kind;
            if (digit(c)) {
                b.append(c);
                i++;
                while (i < n && digit(s.charAt(i))) {
                    b.append(s.charAt(i++));
                }
                if (i < n && s.charAt(i) == ':') {
                    kind = TIME;
                    b.append(s.charAt(i++));
                    while (i < n && (digit(s.charAt(i)) || s.charAt(i) == ':' || s.charAt(i) == '.')) {
                        b.append(s.charAt(i++));
                    }
                } else if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '/' || s.charAt(i) == '.')) {
                    char delim = s.charAt(i);
                    b.append(s.charAt(i++));
                    if (i < n && digit(s.charAt(i))) {
                        kind = delim == '.' ? NUMBER : DATE;
                        while (i < n && digit(s.charAt(i))) {
                            b.append(s.charAt(i++));
                        }
                        if (i < n && s.charAt(i) == delim) {
                            kind = DATE;
                            b.append(s.charAt(i++));
                            while (i < n && (digit(s.charAt(i)) || s.charAt(i) == delim)) {
                                b.append(s.charAt(i++));
                            }
                        }
                    } else {
                        kind = DATE;
                        while (i < n && (digit(s.charAt(i)) || alpha(s.charAt(i)) || s.charAt(i) == delim)) {
                            b.append(lower(s.charAt(i++)));
                        }
                    }
                } else {
                    kind = NUMBER;
                }
            } else if (c == '.') {
                b.append(c);
                i++;
                while (i < n && digit(s.charAt(i))) {
                    b.append(s.charAt(i++));
                }
                kind = NUMBER;
            } else if (alpha(c)) {
                kind = STRING;
                while (i < n && alpha(s.charAt(i))) {
                    b.append(lower(s.charAt(i++)));
                }
                boolean date = false;
                if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '/' || s.charAt(i) == '.')) {
                    date = true;
                } else if (i < n && (s.charAt(i) == '+' || digit(s.charAt(i)))) {
                    date = !WORDS.containsKey(b.toString());
                }
                if (date) {
                    kind = DATE;
                    do {
                        b.append(lower(s.charAt(i++)));
                    } while (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-' || s.charAt(i) == '/'
                            || s.charAt(i) == '_' || s.charAt(i) == '.' || s.charAt(i) == ':' || digit(s.charAt(i))
                            || alpha(s.charAt(i))));
                }
            } else if (c == '+' || c == '-') {
                b.append(c);
                i++;
                while (i < n && space(s.charAt(i))) {
                    i++;
                }
                if (i < n && digit(s.charAt(i))) {
                    kind = ZONE;
                    b.append(s.charAt(i++));
                    while (i < n && (digit(s.charAt(i)) || s.charAt(i) == ':' || s.charAt(i) == '.'
                            || s.charAt(i) == '-')) {
                        b.append(s.charAt(i++));
                    }
                } else if (i < n && alpha(s.charAt(i))) {
                    kind = SPECIAL;
                    while (i < n && alpha(s.charAt(i))) {
                        b.append(lower(s.charAt(i++)));
                    }
                } else {
                    throw badFormat(type, s);
                }
            } else if (punct(c)) {
                i++;
                continue;
            } else {
                throw badFormat(type, s);
            }
            out.add(new Field(b.toString(), kind));
        }
        return out;
    }

    // ------------------------------------------------------------------ DecodeDateTime

    /** DecodeDateTime's state, the struct pg_tm it fills. */
    private static final class Decoder {
        private final String text;
        private final String type;
        private int fmask;
        private int year;
        private int mon;
        private int mday;
        private int yday;
        private int hour;
        private int min;
        private int sec;
        private long fsec;
        private boolean is2digits;
        private boolean julian;

        Decoder(String text, String type) {
            this.text = text;
            this.type = type;
        }

        Value decode(List<Field> fields) throws Bad {
            int ptype = 0;
            boolean haveTextMonth = false;
            boolean epoch = false;
            boolean bc = false;
            int mer = -1;
            for (Field f : fields) {
                int tmask;
                switch (f.kind()) {
                    case DATE -> {
                        if (ptype != 0 || (fmask & (bit(MONTH) | bit(DAY))) == (bit(MONTH) | bit(DAY))) {
                            // Postgres reads it as a time zone (America/New_York, or digits run together with one)
                            throw new Bad(false, "a time zone's name or a run-together time with a zone");
                        }
                        tmask = decodeDate(f.text());
                    }
                    case TIME -> {
                        if (ptype != 0) {
                            if (ptype != ISOTIME) {
                                throw badFormat(type, text);
                            }
                            ptype = 0;
                        }
                        tmask = decodeTime(f.text());
                        if (timeOverflows()) {
                            throw overflow(text);
                        }
                    }
                    case ZONE -> {
                        decodeZone(f.text());
                        tmask = bit(TZ);
                    }
                    case NUMBER -> {
                        if (ptype != 0) {
                            tmask = labelled(f.text(), ptype);
                            ptype = 0;
                        } else {
                            tmask = number(f.text(), haveTextMonth);
                        }
                    }
                    case STRING, SPECIAL -> {
                        int[] word = WORDS.get(f.text());
                        if (word == null) {
                            throw new Bad(false, "a time zone's name, or a word not read here");
                        }
                        int wtype = word[0];
                        int val = word[1];
                        if (wtype == IGNORE) {
                            continue;
                        }
                        tmask = bit(wtype);
                        switch (wtype) {
                            case RESERV -> {
                                if (val != EPOCH) {
                                    throw new Bad(false, "the word '" + f.text() + "'");
                                }
                                tmask = DATE_M | TIME_M | bit(TZ);
                                epoch = true;
                            }
                            case MONTH -> {
                                // a number read as the month before the month's name was its day
                                if ((fmask & bit(MONTH)) != 0 && !haveTextMonth && (fmask & bit(DAY)) == 0
                                        && mon >= 1 && mon <= 31) {
                                    mday = mon;
                                    tmask = bit(DAY);
                                }
                                haveTextMonth = true;
                                mon = val;
                            }
                            case AMPM -> mer = val;
                            case ADBC -> bc = val == BC;
                            case DOW -> {
                                // a day's name is read and not checked
                            }
                            case UNITS -> {
                                tmask = 0;
                                if (ptype != 0) {
                                    throw badFormat(type, text);
                                }
                                ptype = val == JULIAN ? UNITS : -1;
                            }
                            case ISOTIME -> {
                                tmask = 0;
                                if ((fmask & DATE_M) != DATE_M || ptype != 0) {
                                    throw badFormat(type, text);
                                }
                                ptype = ISOTIME;
                            }
                            default -> throw new Bad(false, "a time zone");
                        }
                    }
                    default -> throw badFormat(type, text);
                }
                if ((tmask & fmask) != 0) {
                    throw badFormat(type, text);
                }
                fmask |= tmask;
            }
            if (ptype != 0) {
                throw badFormat(type, text);
            }
            if (epoch) {
                return new Value(LocalDate.of(1970, 1, 1), 0);
            }
            if (bc) {
                throw new Bad(false, "a year before Christ");
            }
            LocalDate date = validate();
            if (mer >= 0 && hour > 12) {
                throw overflow(text);
            }
            if (mer == AM && hour == 12) {
                hour = 0;
            } else if (mer == PM && hour != 12) {
                hour += 12;
            }
            if ((fmask & DATE_M) != DATE_M || date == null) {
                throw badFormat(type, text);
            }
            if (hour > 24 || min > 59 || sec > 60) {
                // run-together digits Postgres reads past their ranges (no other path leaves them so)
                throw new Bad(false, "a time of day past its fields' ranges");
            }
            long micros = ((hour * 60L + min) * 60L + sec) * 1_000_000L + fsec;
            return new Value(date, micros);
        }

        /** A number labelled by the word before it: a Julian day after 'j', a time after 't'. */
        private int labelled(String s, int ptype) throws Bad {
            int dot = s.indexOf('.');
            String whole = dot < 0 ? s : s.substring(0, dot);
            if (whole.isEmpty() || !allDigits(whole)) {
                throw badFormat(type, text);
            }
            if (ptype == UNITS) {
                if (dot >= 0) {
                    throw new Bad(false, "a fractional Julian day");
                }
                long day = integer(whole);
                LocalDate d = LocalDate.ofEpochDay(day - 2440588L);
                year = d.getYear();
                mon = d.getMonthValue();
                mday = d.getDayOfMonth();
                julian = true;
                return DATE_M;
            }
            if (ptype == ISOTIME) {
                int tmask = numberField(s, fmask | DATE_M);
                if (tmask != TIME_M) {
                    throw badFormat(type, text);
                }
                return tmask;
            }
            throw badFormat(type, text);
        }

        /** A field of digits (and a point): a date or time run together, or one of a date's numbers. */
        private int number(String s, boolean haveTextMonth) throws Bad {
            int dot = s.indexOf('.');
            int flen = s.length();
            if (dot >= 0 && (fmask & DATE_M) == 0) {
                return decodeDate(s);
            }
            if (dot >= 0 && dot > 2) {
                return numberField(s, fmask);
            }
            if (flen >= 6 && ((fmask & DATE_M) == 0 || (fmask & TIME_M) == 0)) {
                return numberField(s, fmask);
            }
            return decodeNumber(flen, s, haveTextMonth, fmask);
        }

        /** DecodeDate: a date's fields, a month's name first, then its numbers in context. */
        private int decodeDate(String str) throws Bad {
            List<String> parts = new ArrayList<>();
            int i = 0;
            int n = str.length();
            while (i < n) {
                while (i < n && !digit(str.charAt(i)) && !alpha(str.charAt(i))) {
                    i++;
                }
                if (i >= n) {
                    throw badFormat(type, text);
                }
                int start = i;
                if (digit(str.charAt(i))) {
                    while (i < n && digit(str.charAt(i))) {
                        i++;
                    }
                } else {
                    while (i < n && alpha(str.charAt(i))) {
                        i++;
                    }
                }
                parts.add(str.substring(start, i));
                if (i < n) {
                    i++;
                }
            }
            int tmask = 0;
            int mask = fmask;
            boolean haveTextMonth = false;
            List<String> numbers = new ArrayList<>();
            for (String p : parts) {
                if (!alpha(p.charAt(0))) {
                    numbers.add(p);
                    continue;
                }
                int[] word = WORDS.get(p);
                if (word != null && word[0] == IGNORE) {
                    continue;
                }
                if (word == null || word[0] != MONTH || (mask & bit(MONTH)) != 0) {
                    throw badFormat(type, text);
                }
                mon = word[1];
                haveTextMonth = true;
                mask |= bit(MONTH);
                tmask |= bit(MONTH);
            }
            for (String p : numbers) {
                int dmask = decodeNumber(p.length(), p, haveTextMonth, mask);
                if ((mask & dmask) != 0) {
                    throw badFormat(type, text);
                }
                mask |= dmask;
                tmask |= dmask;
            }
            if ((mask & ~(bit(DOY) | bit(TZ))) != DATE_M) {
                throw badFormat(type, text);
            }
            return tmask;
        }

        /** DecodeNumber: one of a date's numbers, by the fields read so far (ISO's order, else month, day, year). */
        private int decodeNumber(int flen, String str, boolean haveTextMonth, int mask) throws Bad {
            int dot = str.indexOf('.');
            String digits = dot < 0 ? str : str.substring(0, dot);
            if (digits.isEmpty() || !allDigits(digits)) {
                throw badFormat(type, text);
            }
            int val = (int) integer(digits);
            if (dot >= 0) {
                if (dot > 2) {
                    return numberField(str, mask | DATE_M);
                }
                fsec = fraction(str.substring(dot));
            }
            if (flen == 3 && (mask & DATE_M) == bit(YEAR) && val >= 1 && val <= 366) {
                yday = val;
                return bit(DOY) | bit(MONTH) | bit(DAY);
            }
            int tmask;
            switch (mask & DATE_M) {
                case 0 -> {
                    if (flen >= 3) {
                        tmask = bit(YEAR);
                        year = val;
                    } else {
                        tmask = bit(MONTH);
                        mon = val;
                    }
                }
                case 1 << YEAR -> {
                    tmask = bit(MONTH);
                    mon = val;
                }
                case 1 << MONTH -> {
                    if (haveTextMonth && flen >= 3) {
                        tmask = bit(YEAR);
                        year = val;
                    } else {
                        tmask = bit(DAY);
                        mday = val;
                    }
                }
                case (1 << YEAR) | (1 << MONTH) -> {
                    if (haveTextMonth && flen >= 3 && is2digits) {
                        tmask = bit(DAY);
                        mday = year;
                        year = val;
                        is2digits = false;
                    } else {
                        tmask = bit(DAY);
                        mday = val;
                    }
                }
                case 1 << DAY -> {
                    tmask = bit(MONTH);
                    mon = val;
                }
                case (1 << MONTH) | (1 << DAY) -> {
                    tmask = bit(YEAR);
                    year = val;
                }
                case DATE_M -> {
                    return numberField(str, mask);
                }
                default -> throw badFormat(type, text);
            }
            if (tmask == bit(YEAR)) {
                is2digits = flen <= 2;
            }
            return tmask;
        }

        /** DecodeNumberField: digits run together -- yymmdd or yyyymmdd, hhmmss or hhmm -- and a fraction. */
        private int numberField(String s, int mask) throws Bad {
            int dot = s.indexOf('.');
            String str = s;
            if (dot >= 0) {
                fsec = dot + 1 == s.length() ? 0 : fraction(s.substring(dot));
                str = s.substring(0, dot);
            } else if ((mask & DATE_M) != DATE_M && str.length() >= 6) {
                int len = str.length();
                if (!allDigits(str)) {
                    throw badFormat(type, text);
                }
                mday = Integer.parseInt(str.substring(len - 2));
                mon = Integer.parseInt(str.substring(len - 4, len - 2));
                year = (int) integer(str.substring(0, len - 4));
                if (len - 4 == 2) {
                    is2digits = true;
                }
                return DATE_M;
            }
            if ((mask & TIME_M) != TIME_M && allDigits(str)) {
                if (str.length() == 6) {
                    hour = Integer.parseInt(str.substring(0, 2));
                    min = Integer.parseInt(str.substring(2, 4));
                    sec = Integer.parseInt(str.substring(4, 6));
                    return TIME_M;
                }
                if (str.length() == 4) {
                    hour = Integer.parseInt(str.substring(0, 2));
                    min = Integer.parseInt(str.substring(2, 4));
                    sec = 0;
                    return TIME_M;
                }
            }
            throw badFormat(type, text);
        }

        /** DecodeTime: hh:mm[:ss[.fraction]], or mm:ss.fraction (a number's missing digits are 0, as strtoint
         *  reads them). */
        private int decodeTime(String s) throws Bad {
            int[] at = {0};
            long h = digitsAt(s, at);
            if (at[0] >= s.length() || s.charAt(at[0]) != ':') {
                throw badFormat(type, text);
            }
            at[0]++;
            long m = digitsAt(s, at);
            long sc = 0;
            fsec = 0;
            if (at[0] < s.length() && s.charAt(at[0]) == '.') {
                fsec = fraction(s.substring(at[0]));
                sc = m;
                m = h;
                h = 0;
            } else if (at[0] < s.length() && s.charAt(at[0]) == ':') {
                at[0]++;
                sc = digitsAt(s, at);
                if (at[0] < s.length()) {
                    if (s.charAt(at[0]) != '.') {
                        throw badFormat(type, text);
                    }
                    fsec = fraction(s.substring(at[0]));
                }
            } else if (at[0] < s.length()) {
                throw badFormat(type, text);
            }
            if (m > 59 || sc > 60 || fsec > 1_000_000 || h > Integer.MAX_VALUE) {
                throw overflow(text);
            }
            hour = (int) h;
            min = (int) m;
            sec = (int) sc;
            return TIME_M;
        }

        /** The digits at {@code at[0]} as a number (none: 0), {@code at[0]} moved past them. */
        private long digitsAt(String s, int[] at) throws Bad {
            int start = at[0];
            while (at[0] < s.length() && digit(s.charAt(at[0]))) {
                at[0]++;
            }
            return start == at[0] ? 0 : integer(s.substring(start, at[0]));
        }

        private boolean timeOverflows() {
            return hour > 24 || ((hour * 60L + min) * 60L + sec) * 1_000_000L + fsec > 86_400_000_000L;
        }

        /** DecodeTimezone: +hh[:mm[:ss]], or +hhmm run together; in range (then ignored, as Postgres does here). */
        private void decodeZone(String s) throws Bad {
            int[] at = {1};
            long hr = digitsAt(s, at);
            long mn = 0;
            long sc = 0;
            if (at[0] < s.length() && s.charAt(at[0]) == ':') {
                at[0]++;
                mn = digitsAt(s, at);
                if (at[0] < s.length() && s.charAt(at[0]) == ':') {
                    at[0]++;
                    sc = digitsAt(s, at);
                }
            } else if (at[0] == s.length() && s.length() > 3) {
                mn = hr % 100;
                hr = hr / 100;
            }
            if (hr > 15 || mn >= 60 || sc >= 60) {
                throw new Bad(true, "time zone displacement out of range: \"" + text + "\"");
            }
            if (at[0] != s.length()) {
                throw badFormat(type, text);
            }
        }

        /** ValidateDate: two-digit years, a year's day, and the month's days; null for a date not yet complete. */
        private @com.legend.base.Nullable LocalDate validate() throws Bad {
            if ((fmask & bit(YEAR)) != 0 && !julian) {
                if (is2digits) {
                    if (year < 70) {
                        year += 2000;
                    } else if (year < 100) {
                        year += 1900;
                    }
                } else if (year <= 0) {
                    throw overflow(text);
                }
            }
            if ((fmask & bit(DOY)) != 0) {
                LocalDate d = LocalDate.of(year, 1, 1).plusDays(yday - 1L);
                year = d.getYear();
                mon = d.getMonthValue();
                mday = d.getDayOfMonth();
            }
            if ((fmask & bit(MONTH)) != 0 && (mon < 1 || mon > 12)) {
                throw new Bad(true, "date/time field value out of range: \"" + text + "\"");
            }
            if ((fmask & bit(DAY)) != 0 && (mday < 1 || mday > 31)) {
                throw new Bad(true, "date/time field value out of range: \"" + text + "\"");
            }
            if ((fmask & DATE_M) != DATE_M) {
                return null;
            }
            if (year > 9999) {
                throw new Bad(false, "a " + type + " outside the years 1 to 9999");
            }
            try {
                return LocalDate.of(year, mon, mday);
            } catch (DateTimeException e) {
                throw overflow(text);
            }
        }

        /** A fraction's microseconds, as Postgres reckons them: the double of its text times a million, rint. */
        private long fraction(String dotted) throws Bad {
            if (dotted.length() == 1) {
                return 0;
            }
            String digits = dotted.substring(1);
            if (!allDigits(digits)) {
                throw badFormat(type, text);
            }
            return (long) Math.rint(Double.parseDouble("0" + dotted) * 1_000_000);
        }

        private long integer(String digits) throws Bad {
            if (digits.length() > 9 && Long.parseLong(digits.length() > 18 ? "999999999999" : digits)
                    > Integer.MAX_VALUE) {
                throw overflow(text);
            }
            return Long.parseLong(digits);
        }
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!digit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
