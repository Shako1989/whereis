package az.technest.whereis.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * What the model said, as stored in {@code assistant_messages.interpretation} (jsonb). Built only
 * from the provider's output for THIS request — never from anything else — by
 * {@link InterpretationSnapshots}, which also applies the length caps.
 *
 * <p>Deliberately a typed record rather than a {@code String}: Hibernate routes a String through
 * the JSON format mapper and stores a double-encoded scalar, which silently breaks every
 * {@code interpretation->>'…'} query. All fields are nullable; {@code NON_NULL} keeps a SEARCH row
 * down to its three keys.
 *
 * @param validated     whether the payload passed {@link InterpretationValidator} (raw output is stored
 *                      only for NOT_UNDERSTOOD, so the model's actual answer can be inspected)
 * @param locations     REMEMBER — segments outermost first; {@code type} is the raw string for a raw
 *                      snapshot and the parsed {@code LocationType} name for a validated one
 * @param confidence    REMEMBER — the normalized value, same as the column, so a dumped jsonb is self-describing
 * @param rawConfidence REMEMBER — the provider's number rendered as text. A String, never a Double:
 *                      {@link InterpretationSnapshots#confidenceOf} nulls out NaN/Infinity/out-of-range,
 *                      which is exactly the case worth seeing, and Jackson writes a Double NaN as a
 *                      bare {@code NaN} token that PostgreSQL rejects on insert
 * @param keywords      SEARCH — the keywords the search actually ran with
 * @param usedFallback  SEARCH — the validator produced nothing and the normalized sentence was used
 * @param rawKeywords   SEARCH NOT_UNDERSTOOD — what the model returned before validation dropped it all
 * @param offeredSpaces REMEMBER — the user's own space names as they were handed to the model. This is
 *                      request context, not model output, and it is the field that makes a wrong space
 *                      diagnosable: without it a row showing {@code spaceName="Work"} cannot be told
 *                      apart from "the model was never offered the right space"
 * @param raw           REMEMBER, validated snapshots only — the pre-validation answer, because the
 *                      validator rewrites fields in ways that change the diagnosis (an unsafe
 *                      {@code spaceName} is degraded to null, names are cleaned). Always {@code null}
 *                      inside a raw snapshot, so the structure never nests more than one level
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterpretationSnapshot(
        Boolean validated,
        String itemName,
        String itemDescription,
        String spaceName,
        List<Segment> locations,
        BigDecimal confidence,
        String rawConfidence,
        List<String> keywords,
        Boolean usedFallback,
        List<String> rawKeywords,
        List<String> offeredSpaces,
        InterpretationSnapshot raw
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Segment(String name, String type) {
    }
}
