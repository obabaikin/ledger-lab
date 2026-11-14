package com.ledgerlab.control.api;

import com.ledgerlab.control.bank.BankService;
import com.ledgerlab.control.bank.BankView;
import com.ledgerlab.control.bank.PeriodDetails;
import com.ledgerlab.control.bank.PeriodList;
import com.ledgerlab.control.bank.SeriesService;
import com.ledgerlab.control.bank.SeriesView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Open to every visitor: no session is asked for. */
@RestController
@RequestMapping(BankController.BANK_PATH)
public class BankController {

    public static final String BANK_PATH = "/api/bank";

    private final BankService bank;
    private final SeriesService series;

    public BankController(BankService bank, SeriesService series) {
        this.bank = bank;
        this.series = series;
    }

    @Operation(summary = "The bank now: totals, clients, instances, objectives")
    @ApiResponse(responseCode = "200", description = "The bank")
    @GetMapping
    public BankView bank() {
        return bank.overview();
    }

    @Operation(summary = "The last periods that are over")
    @ApiResponse(responseCode = "200", description = "The periods, newest first")
    @ApiResponse(responseCode = "400", description = "limit is not between 1 and 100", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/periods")
    public PeriodList periods(@RequestParam(defaultValue = "" + BankService.DEFAULT_PERIODS) int limit) {
        return bank.endedPeriods(limit);
    }

    @Operation(summary = "A period that is over, with its report once reconciled")
    @ApiResponse(responseCode = "200", description = "The period")
    @ApiResponse(responseCode = "404", description = "No such period, or it is not over", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/periods/{periodStart}")
    public PeriodDetails period(@PathVariable Instant periodStart) {
        return bank.period(periodStart);
    }

    @Operation(summary = "The bank's charts from monitoring")
    @ApiResponse(responseCode = "200", description = "The charts; without series if monitoring could not be read")
    @ApiResponse(responseCode = "400", description = "A range other than those served", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/series")
    public SeriesView series(
        @Parameter(description = "The time the charts cover: 5m, 30m or 2h; omit for 2h")
        @RequestParam(required = false) @Nullable String range
    ) throws InterruptedException, ExecutionException {
        return series.series(range);
    }
}
