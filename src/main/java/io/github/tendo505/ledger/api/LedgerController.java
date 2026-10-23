package io.github.tendo505.ledger.api;

import io.github.tendo505.ledger.core.Account;
import io.github.tendo505.ledger.core.Audit;
import io.github.tendo505.ledger.core.Hold;
import io.github.tendo505.ledger.core.Idempotency;
import io.github.tendo505.ledger.core.LedgerService;
import io.github.tendo505.ledger.core.Transaction;
import io.github.tendo505.ledger.events.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Validated
public class LedgerController {
    private static final String IDEMPOTENCY_HELP =
            "Required. Retrying with the same key returns the original response instead of repeating the operation.";

    private final LedgerService ledger;
    private final Idempotency idempotency;
    private final Audit audit;
    private final WebhookService webhooks;

    public LedgerController(LedgerService ledger, Idempotency idempotency, Audit audit, WebhookService webhooks) {
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.webhooks = webhooks;
    }

    // ---------------- accounts ----------------

    @PostMapping("/accounts")
    @Operation(summary = "Open an account")
    public ResponseEntity<Account> createAccount(@Valid @RequestBody Requests.CreateAccount req) {
        Account a = ledger.createAccount(req.name(), req.currency(), Boolean.TRUE.equals(req.allowNegative()));
        return ResponseEntity.created(URI.create("/v1/accounts/" + a.id())).body(a);
    }

    @GetMapping("/accounts/{id}")
    @Operation(summary = "An account's balance, reserved amount and available balance")
    public Account account(@PathVariable String id) {
        return ledger.account(id);
    }

    public record EntriesPage(List<Transaction.Entry> data, Long nextBefore) {}

    @GetMapping("/accounts/{id}/entries")
    @Operation(summary = "An account's statement, newest first", description = "Pass nextBefore from one page as before to get the next.")
    public EntriesPage entries(@PathVariable String id,
                               @RequestParam(required = false) Long before,
                               @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        List<Transaction.Entry> page = ledger.entries(id, before, limit);
        return new EntriesPage(page, page.size() == limit ? page.getLast().id() : null);
    }

    // ---------------- transfers ----------------

    @PostMapping("/transfers")
    @Operation(summary = "Move money between two accounts of the same currency")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = Transaction.class)))
    public ResponseEntity<String> transfer(@Parameter(description = IDEMPOTENCY_HELP) @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody Requests.Transfer req) {
        return respond(idempotency.run(key, req, HttpStatus.CREATED,
                () -> ledger.transfer(req.from(), req.to(), req.amount(), req.currency(), req.description())));
    }

    @GetMapping("/transactions/{id}")
    @Operation(summary = "A transaction and its entries")
    public Transaction transaction(@PathVariable String id) {
        return ledger.transaction(id);
    }

    // ---------------- holds ----------------

    @PostMapping("/holds")
    @Operation(summary = "Reserve funds, like a card authorization", description = "The amount stops being available until the hold is captured, voided or expires.")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = Hold.class)))
    public ResponseEntity<String> createHold(@Parameter(description = IDEMPOTENCY_HELP) @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody Requests.CreateHold req) {
        Duration ttl = Duration.ofSeconds(req.ttlSeconds() == null ? 600 : req.ttlSeconds());
        return respond(idempotency.run(key, req, HttpStatus.CREATED,
                () -> ledger.createHold(req.account(), req.destination(), req.amount(), req.currency(), ttl)));
    }

    @GetMapping("/holds/{id}")
    public Hold hold(@PathVariable String id) {
        return ledger.hold(id);
    }

    @PostMapping("/holds/{id}/capture")
    @Operation(summary = "Pay all or part of a hold to its destination; the rest is released")
    public Hold capture(@PathVariable String id, @Valid @RequestBody(required = false) Requests.Capture req) {
        return ledger.capture(id, req == null ? null : req.amount());
    }

    @PostMapping("/holds/{id}/void")
    @Operation(summary = "Cancel a hold and release its funds")
    public Hold voidHold(@PathVariable String id) {
        return ledger.voidHold(id);
    }

    // ---------------- webhooks and audit ----------------

    @PostMapping("/webhooks")
    @Operation(summary = "Subscribe a URL to all events", description = "The response includes the signing secret; it is not shown again.")
    public ResponseEntity<WebhookService.Webhook> createWebhook(@Valid @RequestBody Requests.CreateWebhook req) {
        WebhookService.Webhook hook = webhooks.create(req.url());
        return ResponseEntity.created(URI.create("/v1/webhooks/" + hook.id())).body(hook);
    }

    @GetMapping("/webhooks")
    public List<WebhookService.Webhook> webhooks() {
        return webhooks.list();
    }

    @GetMapping("/audit")
    @Operation(summary = "Re-derive every balance from the entries and check the books balance")
    public Audit.Report audit() {
        return audit.run();
    }

    private static ResponseEntity<String> respond(Idempotency.Response r) {
        return ResponseEntity.status(r.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", String.valueOf(r.replayed()))
                .body(r.body());
    }
}
