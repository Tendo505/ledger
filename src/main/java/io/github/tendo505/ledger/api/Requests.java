package io.github.tendo505.ledger.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request bodies. Amounts are integers in minor units: 1050 means 10.50. */
public final class Requests {
    private Requests() {}

    public record CreateAccount(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like USD") String currency,
            @Schema(description = "Funding accounts (e.g. 'bank') may go negative; customer accounts may not") Boolean allowNegative) {}

    public record Transfer(
            @NotBlank String from,
            @NotBlank String to,
            @Positive @Schema(description = "minor units", example = "2500") long amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 500) String description) {}

    public record CreateHold(
            @NotBlank String account,
            @NotBlank String destination,
            @Positive long amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @Positive @Max(604_800) @Schema(description = "how long the funds stay reserved; default 600 s") Long ttlSeconds) {}

    public record Capture(@Positive @Schema(description = "omit to capture the whole hold") Long amount) {}

    public record CreateWebhook(@NotBlank @Size(max = 500) String url) {}
}
