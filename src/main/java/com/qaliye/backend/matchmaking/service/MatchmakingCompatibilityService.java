package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceValues;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Compatibility scoring for a requester/candidate pair (design §6, §11).
 *
 * <p>Pure, dependency-light component — the caller prepares a
 * {@link SideInput} per user combining their preference values and resolved
 * profile facts. Directional dims are evaluated asymmetrically (each side's
 * prefs vs the OTHER user's facts); the five alignment dims are symmetric and
 * a single outcome counts in both directions.
 *
 * <p>{@code forward}  = satisfied requester-side active dims / active × 100
 * <br>{@code reverse}  = satisfied candidate-side active dims / active × 100
 * <br>{@code overall}  = round((forward + reverse) / 2)
 * <br>{@code active}   = 0 → 100 (no requirements = nothing violated)
 */
@Service
public class MatchmakingCompatibilityService {

    public record CompatibilityResult(
            int forwardPercentage,
            int reversePercentage,
            int overallPercentage,
            List<DimensionResult> details,
            String reason) {}

    public record DimensionResult(
            String dimension, boolean matched, String label) {}

    /** Resolved profile facts for one user (the "other side" of a directional check). */
    public record ProfileFacts(
            Integer age,
            Integer heightCm,
            String countryCode,
            Boolean hasChildren,
            Boolean wantsChildren,
            String religion,
            String educationLevel,
            String maritalStatus,
            String smokingValue,
            String drinkingValue,
            UUID[] languageIds,
            UUID[] ethnicityIds) {}

    /** One side of the pairing: their preferences + their profile facts. */
    public record SideInput(PreferenceValues prefs, ProfileFacts facts) {}

    /** Builds a {@link SideInput} from a live preference row + profile facts. */
    public static SideInput side(PreferenceRow row, ProfileFacts facts) {
        return new SideInput(toValues(row), facts);
    }

    /** Builds a {@link SideInput} from (possibly overridden) values + profile facts. */
    public static SideInput side(PreferenceValues values, ProfileFacts facts) {
        return new SideInput(values, facts);
    }

    /** Converts a persisted row to the value record the scorer consumes. */
    public static PreferenceValues toValues(PreferenceRow r) {
        return new PreferenceValues(
                r.minAge(), r.maxAge(), r.minHeightCm(), r.maxHeightCm(),
                r.specificCountryCodes(), r.hasChildrenPreference(),
                r.wantsChildrenPreference(), r.religionPreferences(),
                r.educationLevels(), r.maritalStatuses(),
                r.smokingPreferences(), r.drinkingPreferences(),
                r.languagePreferenceIds(), r.ethnicityPreferenceIds(),
                r.userNotes(),
                r.marriageTimeline(), r.longDistanceRelationship(),
                r.familyInvolvement(), r.religionImportant(), r.willingToRelocate());
    }

    /**
     * Scores the pairing.
     *
     * @param requester the requester side — preferences may be the EFFECTIVE
     *                  set (live values + admin overrides merged)
     * @param candidate the candidate side — live preferences
     */
    public CompatibilityResult score(SideInput requester, SideInput candidate) {
        List<DimensionResult> details = new ArrayList<>();

        Counts forward = evalDirectional(requester.prefs(), candidate.facts(),
                "candidate", details);
        Counts reverse = evalDirectional(candidate.prefs(), requester.facts(),
                "requester", details);
        Counts alignment = evalAlignment(requester.prefs(), candidate.prefs(),
                requester.facts(), candidate.facts(), details);

        int fwdActive = forward.active() + alignment.active();
        int fwdMatched = forward.matched() + alignment.matched();
        int revActive = reverse.active() + alignment.active();
        int revMatched = reverse.matched() + alignment.matched();

        int fwdPct = fwdActive == 0 ? 100 : Math.round(fwdMatched * 100f / fwdActive);
        int revPct = revActive == 0 ? 100 : Math.round(revMatched * 100f / revActive);
        int overall = Math.round((fwdPct + revPct) / 2f);

        String reason = buildReason(details);
        return new CompatibilityResult(fwdPct, revPct, overall,
                List.copyOf(details), reason);
    }

    // ── Directional dimensions ───────────────────────────────────────────

    private record Counts(int active, int matched) {
        Counts plus(boolean active, boolean matched) {
            return new Counts(this.active + (active ? 1 : 0),
                              this.matched + (matched ? 1 : 0));
        }
    }

    private Counts evalDirectional(PreferenceValues p, ProfileFacts other,
                                   String who, List<DimensionResult> details) {
        Counts c = new Counts(0, 0);

        if (p.minAge() != null || p.maxAge() != null) {
            boolean ok = other.age() != null
                    && (p.minAge() == null || other.age() >= p.minAge())
                    && (p.maxAge() == null || other.age() <= p.maxAge());
            details.add(new DimensionResult("age", ok,
                    who + " age " + val(other.age()) + rangeLabel(p.minAge(), p.maxAge())));
            c = c.plus(true, ok);
        }
        if (p.minHeightCm() != null || p.maxHeightCm() != null) {
            boolean ok = other.heightCm() != null
                    && (p.minHeightCm() == null || other.heightCm() >= p.minHeightCm())
                    && (p.maxHeightCm() == null || other.heightCm() <= p.maxHeightCm());
            details.add(new DimensionResult("height", ok,
                    who + " height " + val(other.heightCm()) + "cm"
                            + rangeLabel(p.minHeightCm(), p.maxHeightCm())));
            c = c.plus(true, ok);
        }
        if (nonEmpty(p.specificCountryCodes())) {
            boolean ok = other.countryCode() != null
                    && contains(p.specificCountryCodes(), other.countryCode());
            details.add(new DimensionResult("specific_countries", ok,
                    who + " country " + val(other.countryCode())
                            + (ok ? " in " : " not in ") + Arrays.toString(p.specificCountryCodes())));
            c = c.plus(true, ok);
        }
        if (isSet(p.hasChildrenPreference())) {
            boolean ok = switch (p.hasChildrenPreference()) {
                case "yes" -> Boolean.TRUE.equals(other.hasChildren());
                case "no"  -> !Boolean.TRUE.equals(other.hasChildren());
                default    -> false;
            };
            details.add(new DimensionResult("has_children", ok,
                    who + " has children: " + factVal(other.hasChildren())
                            + ", pref " + p.hasChildrenPreference()));
            c = c.plus(true, ok);
        }
        if (isSet(p.wantsChildrenPreference())) {
            boolean ok = switch (p.wantsChildrenPreference()) {
                case "yes"                -> Boolean.TRUE.equals(other.wantsChildren());
                case "no"                 -> !Boolean.TRUE.equals(other.wantsChildren());
                case "not_sure"           -> other.wantsChildren() == null;
                case "open_to_discussion" -> !Boolean.FALSE.equals(other.wantsChildren());
                default                   -> false;
            };
            details.add(new DimensionResult("wants_children", ok,
                    who + " wants children: " + factVal(other.wantsChildren())
                            + ", pref " + p.wantsChildrenPreference()));
            c = c.plus(true, ok);
        }
        if (nonEmpty(p.religionPreferences())) {
            boolean ok = other.religion() != null
                    && contains(p.religionPreferences(), other.religion());
            details.add(new DimensionResult("religion", ok,
                    who + " religion " + val(other.religion())
                            + (ok ? " in " : " not in ") + Arrays.toString(p.religionPreferences())));
            c = c.plus(true, ok);
        }
        if (nonEmpty(p.educationLevels())) {
            boolean ok = other.educationLevel() != null
                    && contains(p.educationLevels(), other.educationLevel());
            details.add(new DimensionResult("education", ok,
                    who + " education " + val(other.educationLevel())
                            + (ok ? " in " : " not in ") + Arrays.toString(p.educationLevels())));
            c = c.plus(true, ok);
        }
        if (nonEmpty(p.maritalStatuses())) {
            boolean ok = other.maritalStatus() != null
                    && contains(p.maritalStatuses(), other.maritalStatus());
            details.add(new DimensionResult("marital_status", ok,
                    who + " marital status " + val(other.maritalStatus())
                            + (ok ? " in " : " not in ") + Arrays.toString(p.maritalStatuses())));
            c = c.plus(true, ok);
        }
        if (nonEmpty(p.smokingPreferences())) {
            boolean ok = other.smokingValue() != null
                    && contains(p.smokingPreferences(), other.smokingValue());
            details.add(new DimensionResult("smoking", ok,
                    who + " smoking " + val(other.smokingValue())
                            + (ok ? " in " : " not in ") + Arrays.toString(p.smokingPreferences())));
            c = c.plus(true, ok);
        }
        if (nonEmpty(p.drinkingPreferences())) {
            boolean ok = other.drinkingValue() != null
                    && contains(p.drinkingPreferences(), other.drinkingValue());
            details.add(new DimensionResult("drinking", ok,
                    who + " drinking " + val(other.drinkingValue())
                            + (ok ? " in " : " not in ") + Arrays.toString(p.drinkingPreferences())));
            c = c.plus(true, ok);
        }
        if (nonEmpty(p.languagePreferenceIds())) {
            boolean ok = overlaps(p.languagePreferenceIds(), other.languageIds());
            details.add(new DimensionResult("languages", ok,
                    ok ? "shared languages" : "no shared languages"));
            c = c.plus(true, ok);
        }
        if (nonEmpty(p.ethnicityPreferenceIds())) {
            boolean ok = overlaps(p.ethnicityPreferenceIds(), other.ethnicityIds());
            details.add(new DimensionResult("ethnicity", ok,
                    ok ? "shared ethnicities" : "no shared ethnicities"));
            c = c.plus(true, ok);
        }
        return c;
    }

    // ── Alignment dimensions (symmetric — one outcome, both directions) ──

    private Counts evalAlignment(PreferenceValues a, PreferenceValues b,
                                 ProfileFacts aFacts, ProfileFacts bFacts,
                                 List<DimensionResult> details) {
        Counts c = new Counts(0, 0);

        if (a.marriageTimeline() != null && b.marriageTimeline() != null) {
            boolean ok = a.marriageTimeline().equals(b.marriageTimeline())
                    || "NOT_SURE".equals(a.marriageTimeline())
                    || "NOT_SURE".equals(b.marriageTimeline());
            details.add(new DimensionResult("marriage_timeline", ok,
                    a.marriageTimeline() + " vs " + b.marriageTimeline()));
            c = c.plus(true, ok);
        }
        if (a.longDistanceRelationship() != null && b.longDistanceRelationship() != null) {
            boolean sameCountry = aFacts.countryCode() != null
                    && aFacts.countryCode().equals(bFacts.countryCode());
            boolean ok = ("YES".equals(a.longDistanceRelationship())
                    && "YES".equals(b.longDistanceRelationship()))
                    || sameCountry;
            details.add(new DimensionResult("long_distance", ok,
                    a.longDistanceRelationship() + " vs " + b.longDistanceRelationship()
                            + (sameCountry ? " (same country)" : "")));
            c = c.plus(true, ok);
        }
        if (a.familyInvolvement() != null && b.familyInvolvement() != null) {
            boolean ok = a.familyInvolvement().equals(b.familyInvolvement());
            details.add(new DimensionResult("family_involvement", ok,
                    a.familyInvolvement() + " vs " + b.familyInvolvement()));
            c = c.plus(true, ok);
        }
        if (a.religionImportant() != null && b.religionImportant() != null) {
            boolean ok = a.religionImportant().equals(b.religionImportant());
            details.add(new DimensionResult("religion_important", ok,
                    a.religionImportant() + " vs " + b.religionImportant()));
            c = c.plus(true, ok);
        }
        if (a.willingToRelocate() != null && b.willingToRelocate() != null) {
            boolean ok = a.willingToRelocate().equals(b.willingToRelocate());
            details.add(new DimensionResult("willing_to_relocate", ok,
                    a.willingToRelocate() + " vs " + b.willingToRelocate()));
            c = c.plus(true, ok);
        }
        return c;
    }

    // ── Reason generation ────────────────────────────────────────────────

    /** Order in which matched dims are considered for the "why" sentence. */
    private static final List<String> REASON_PRIORITY = List.of(
            "marriage_timeline", "long_distance", "family_involvement",
            "religion_important", "willing_to_relocate", "religion",
            "languages", "wants_children", "has_children", "education",
            "ethnicity", "marital_status", "age", "height",
            "specific_countries", "smoking", "drinking");

    private static final int MAX_REASON_PHRASES = 3;

    private String buildReason(List<DimensionResult> details) {
        Set<String> matched = new HashSet<>();
        for (DimensionResult d : details) {
            if (d.matched()) matched.add(d.dimension());
        }
        List<String> phrases = new ArrayList<>();
        for (String dim : REASON_PRIORITY) {
            if (matched.contains(dim)) {
                String phrase = reasonPhrase(dim, details);
                if (phrase != null) phrases.add(phrase);
            }
            if (phrases.size() >= MAX_REASON_PHRASES) break;
        }
        if (phrases.isEmpty()) {
            return "Your matchmaker selected this profile for you";
        }
        return String.join(". ", phrases) + ".";
    }

    private String reasonPhrase(String dim, List<DimensionResult> details) {
        return switch (dim) {
            case "marriage_timeline" ->
                    "You both are looking for marriage within a similar timeframe";
            case "long_distance"      -> longDistancePhrase(details);
            case "family_involvement" -> "You share the same preference on family involvement";
            case "religion_important" -> "Religion matters equally to both of you";
            case "willing_to_relocate" -> "You share the same position on relocating";
            case "religion"           -> "Your religious preferences align";
            case "languages"          -> "You share common languages";
            case "wants_children"     -> "Your plans for children align";
            case "has_children"       -> "Your views on children align";
            case "education"          -> "Your education preferences align";
            case "ethnicity"          -> "Your ethnicity preferences align";
            case "marital_status"     -> "Your marital-status preferences align";
            case "age"                -> "Your age preferences align";
            case "height"             -> "Your height preferences align";
            case "specific_countries" -> "Your location preferences align";
            case "smoking"            -> "Your smoking preferences align";
            case "drinking"           -> "Your drinking preferences align";
            default                   -> null;
        };
    }

    private String longDistancePhrase(List<DimensionResult> details) {
        for (DimensionResult d : details) {
            if ("long_distance".equals(d.dimension()) && d.matched()) {
                return d.label().contains("same country")
                        ? "You both live in the same country"
                        : "You both are open to a long-distance relationship";
            }
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean nonEmpty(String[] arr) {
        return arr != null && arr.length > 0;
    }

    private static boolean nonEmpty(UUID[] arr) {
        return arr != null && arr.length > 0;
    }

    /** A children-preference enum is "set" when non-null and not 'any'. */
    private static boolean isSet(String value) {
        return value != null && !"any".equalsIgnoreCase(value);
    }

    private static boolean contains(String[] arr, String value) {
        for (String s : arr) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static boolean overlaps(UUID[] prefs, UUID[] facts) {
        if (facts == null || facts.length == 0) return false;
        Set<UUID> factSet = new HashSet<>(Arrays.asList(facts));
        for (UUID p : prefs) {
            if (factSet.contains(p)) return true;
        }
        return false;
    }

    private static String rangeLabel(Integer min, Integer max) {
        String lo = min == null ? "?" : String.valueOf(min);
        String hi = max == null ? "?" : String.valueOf(max);
        return " within " + lo + "-" + hi;
    }

    private static String val(Object v) {
        return v == null ? "unknown" : v.toString().toUpperCase(Locale.ROOT);
    }

    private static String factVal(Boolean b) {
        return b == null ? "unspecified" : (b ? "yes" : "no");
    }
}
