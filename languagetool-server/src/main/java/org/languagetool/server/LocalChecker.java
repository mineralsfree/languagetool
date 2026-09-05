/* LanguageTool, a natural language style checker
 * Copyright (C) 2026 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.languagetool.CheckResults;
import org.languagetool.DetectedLanguage;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.markup.AnnotatedText;
import org.languagetool.markup.AnnotatedTextBuilder;
import org.languagetool.rules.RuleMatch;
import org.languagetool.tools.RuleMatchesAsJsonSerializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runs LanguageTool directly against a piece of text and returns the same JSON
 * shape that the {@code /v2/check} HTTP endpoint produces (via
 * {@link RuleMatchesAsJsonSerializer#ruleMatchesToJson2}). Use this when you
 * want LanguageTool's analysis without the HTTP overhead — e.g. in-process
 * spell checking from another JVM tool, batch jobs, or test harnesses.
 *
 * <p>Programmatic use:
 * <pre>{@code
 *   String json = LocalChecker.checkAsJson("Прывiтанне свет", "be-BY");
 * }</pre>
 *
 * <p>The output is byte-for-byte identical to a {@code /v2/check} response
 * with one deliberate exception: the {@code language.detectedLanguage} block
 * reports {@code source: "given"} and {@code confidence: 1.0} because this
 * checker does not run language auto-detection — it trusts {@code langCode}.
 * Skipping detection is what makes this faster than the HTTP path; if you
 * need detection, use the HTTP server.
 *
 * <p>CLI use:
 * <pre>
 *   java -cp ... org.languagetool.server.LocalChecker --language be-BY --text "Прывiтанне свет"
 *   echo "Прывiтанне свет" | java -cp ... org.languagetool.server.LocalChecker --language be-BY
 *   java -cp ... org.languagetool.server.LocalChecker --language be-BY --text-file input.txt
 * </pre>
 *
 * @since 6.7
 */
public final class LocalChecker {

  // Match the value V2TextChecker uses (TextChecker.CONTEXT_SIZE) so that the
  // "context" field in each match is byte-for-byte identical to /v2/check.
  private static final int CONTEXT_SIZE = 40;

  // Cache JLanguageTool per language code. Building one is expensive
  // (loads dictionaries + rules); reusing makes repeated calls ~30-100x
  // faster than building a fresh instance every time.
  private static final ConcurrentMap<String, JLanguageTool> LT_CACHE = new ConcurrentHashMap<>();

  private LocalChecker() {
  }

  /**
   * Run LanguageTool on {@code text} for the given language code and return
   * a JSON string identical in shape to a {@code /v2/check} response.
   *
   * @param text plain UTF-8 text to check
   * @param langCode short code (e.g. {@code "be"}) or long code
   *   (e.g. {@code "be-BY"}) accepted by
   *   {@link Languages#getLanguageForShortCode(String)}
   * @return JSON document with {@code software}, {@code language}, {@code matches}, etc.
   * @throws IOException if the language model fails to load
   */
  public static String checkAsJson(String text, String langCode) throws IOException {
    return checkAsJson(text, langCode, false);
  }

  /**
   * Same as {@link #checkAsJson(String, String)} but optionally skips
   * suggested-replacement computation. When {@code noSuggestions} is
   * {@code true}, every match's {@code replacements} array in the JSON is
   * empty and the underlying (often expensive, e.g. Levenshtein-over-
   * dictionary) suggestion lookup is bypassed. Useful for callers that only
   * need rule IDs / categories / counts.
   *
   * @since 6.7
   */
  public static String checkAsJson(String text, String langCode, boolean noSuggestions) throws IOException {
    return checkAsJson(text, langCode, noSuggestions, false);
  }

  /**
   * Same as {@link #checkAsJson(String, String, boolean)} but optionally
   * inserts a {@code "_meta"} object containing per-call timings (in
   * milliseconds, as doubles) at the top of the JSON response. Used by the
   * benchmarking harness; production callers should leave {@code withTiming}
   * false so the wire format matches {@code /v2/check} exactly.
   */
  public static String checkAsJson(String text, String langCode, boolean noSuggestions, boolean withTiming) throws IOException {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    if (langCode == null) {
      throw new IllegalArgumentException("langCode must not be null");
    }
    Language language = Languages.getLanguageForShortCode(langCode);
    JLanguageTool lt = LT_CACHE.computeIfAbsent(language.getShortCodeWithCountryAndVariant(),
      k -> new JLanguageTool(language));
    AnnotatedText annotated = new AnnotatedTextBuilder().addText(text).build();
    long checkStart = withTiming ? System.nanoTime() : 0L;
    List<RuleMatch> matches = lt.check(annotated, true,
      JLanguageTool.ParagraphHandling.NORMAL, null, JLanguageTool.Mode.ALL,
      JLanguageTool.Level.DEFAULT);
    if (noSuggestions) {
      // Drop pending lazy suggestions (avoids the expensive Levenshtein /
      // dictionary lookup) and clear any eagerly-computed ones so the
      // serializer emits empty `replacements` arrays.
      for (RuleMatch m : matches) {
        m.discardLazySuggestedReplacements();
        m.setSuggestedReplacements(Collections.emptyList());
      }
    }
    long checkEnd = withTiming ? System.nanoTime() : 0L;

    DetectedLanguage detected = new DetectedLanguage(language, language, 1.0f, "given");
    CheckResults result = new CheckResults(matches, Collections.emptyList());

    RuleMatchesAsJsonSerializer serializer = new RuleMatchesAsJsonSerializer(0, language);
    long serializeStart = withTiming ? System.nanoTime() : 0L;
    String json = serializer.ruleMatchesToJson2(
      Collections.singletonList(result),
      Collections.emptyList(),
      annotated,
      CONTEXT_SIZE,
      detected,
      null,
      false,
      JLanguageTool.Mode.ALL);
    long serializeEnd = withTiming ? System.nanoTime() : 0L;

    if (!withTiming) {
      return json;
    }
    // Inject "_meta": {...} as the first key. The serializer always emits
    // an object starting with '{' followed by a key, so prefixing works.
    String meta = String.format(java.util.Locale.ROOT,
      "\"_meta\":{\"check_ms\":%.3f,\"serialize_ms\":%.3f,\"chars\":%d,\"matches\":%d},",
      (checkEnd - checkStart) / 1_000_000.0,
      (serializeEnd - serializeStart) / 1_000_000.0,
      text.length(),
      matches.size());
    if (json.length() < 2 || json.charAt(0) != '{') {
      return json;  // shouldn't happen, but don't corrupt output
    }
    return "{" + meta + json.substring(1);
  }

  public static void main(String[] args) throws IOException {
    String langCode = "be-BY";
    String text = null;
    String textFile = null;
    boolean stdinLoop = false;
    boolean noSuggestions = false;
    boolean withTiming = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "-h":
        case "--help":
          printUsage(System.out);
          return;
        case "-l":
        case "--language":
          langCode = requireValue(args, ++i, "--language");
          break;
        case "-t":
        case "--text":
          text = requireValue(args, ++i, "--text");
          break;
        case "--text-file":
          textFile = requireValue(args, ++i, "--text-file");
          break;
        case "--stdin-loop":
          stdinLoop = true;
          break;
        case "--no-suggestions":
          noSuggestions = true;
          break;
        case "--with-timing":
          withTiming = true;
          break;
        default:
          System.err.println("Unknown argument: " + args[i]);
          printUsage(System.err);
          System.exit(2);
      }
    }
    if (stdinLoop) {
      if (text != null || textFile != null) {
        System.err.println("--stdin-loop is incompatible with --text / --text-file");
        System.exit(2);
      }
      runStdinLoop(langCode, noSuggestions, withTiming);
      return;
    }
    if (text != null && textFile != null) {
      System.err.println("Use either --text or --text-file, not both.");
      System.exit(2);
    }
    String input;
    if (text != null) {
      input = text;
    } else if (textFile != null) {
      input = new String(Files.readAllBytes(Paths.get(textFile)), StandardCharsets.UTF_8);
    } else {
      input = readAll(System.in);
    }
    String json = checkAsJson(input, langCode, noSuggestions, withTiming);
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    out.println(json);
  }

  /**
   * Long-running line protocol designed for apps that spawn this process once
   * and stream many requests through stdin. Robust to text that contains
   * newlines because each frame is JSON.
   *
   * <p>Protocol:
   * <ul>
   *   <li>The process eagerly loads the language model, then writes the
   *       single line {@code READY} to <b>stderr</b> and starts reading stdin.
   *       Wait for that line if you want to gate your first request.</li>
   *   <li>Each input line is a JSON-encoded UTF-8 string — i.e. the text
   *       wrapped as a JSON value. From Python: {@code json.dumps(text)}.
   *       From shell: {@code printf '%s\n' "\"hello\""}.</li>
   *   <li>For each input line, exactly one line of JSON is written to stdout
   *       (and flushed) — the same shape as a {@code /v2/check} response.</li>
   *   <li>If a line is not valid JSON or processing throws, a single-line
   *       JSON error object is emitted instead, and the loop continues.</li>
   *   <li>EOF on stdin terminates the loop and exits 0.</li>
   * </ul>
   */
  private static void runStdinLoop(String langCode, boolean noSuggestions, boolean withTiming) throws IOException {
    // Warm the cache before signaling ready so the first real request is fast.
    checkAsJson("", langCode, noSuggestions, withTiming);
    PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    ObjectMapper mapper = new ObjectMapper();
    err.println("READY");
    String line;
    while ((line = in.readLine()) != null) {
      try {
        String text = mapper.readValue(line, String.class);
        out.println(checkAsJson(text, langCode, noSuggestions, withTiming));
      } catch (Exception e) {
        out.println("{\"error\":\"" + escapeJson(e.getClass().getSimpleName() + ": " + e.getMessage()) + "\"}");
      }
      out.flush();
    }
  }

  private static String escapeJson(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }

  private static String requireValue(String[] args, int idx, String flag) {
    if (idx >= args.length || args[idx].startsWith("--")) {
      throw new IllegalArgumentException("Missing argument for '" + flag + "'");
    }
    return args[idx];
  }

  private static String readAll(java.io.InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      char[] buf = new char[4096];
      int n;
      while ((n = r.read(buf)) != -1) {
        sb.append(buf, 0, n);
      }
    }
    return sb.toString();
  }

  private static void printUsage(PrintStream out) {
    out.println("Usage: LocalChecker [--language CODE] [--no-suggestions]");
    out.println("                    [--text TEXT | --text-file PATH | --stdin-loop]");
    out.println();
    out.println("Runs LanguageTool against a string in-process and prints the same JSON");
    out.println("that the HTTP /v2/check endpoint returns. Reads from stdin if no text is");
    out.println("supplied.");
    out.println();
    out.println("Options:");
    out.println("  -l, --language CODE   language short or long code (default: be-BY)");
    out.println("  -t, --text TEXT       text to check (UTF-8)");
    out.println("      --text-file PATH  read text from a UTF-8 file");
    out.println("      --no-suggestions  skip suggested-replacement computation; the");
    out.println("                        'replacements' arrays in the response are empty.");
    out.println("                        Big speedup when callers only want rule IDs/counts.");
    out.println("      --stdin-loop      long-running mode: each stdin line is a JSON-encoded");
    out.println("                        string (e.g. json.dumps(text) in Python); each stdout");
    out.println("                        line is one /v2/check JSON response. Writes 'READY'");
    out.println("                        to stderr after warmup. EOF on stdin exits the process.");
    out.println("  -h, --help            show this help");
  }
}
