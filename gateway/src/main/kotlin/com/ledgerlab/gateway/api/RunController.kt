package com.ledgerlab.gateway.api

import com.ledgerlab.gateway.domain.RunSummary
import com.ledgerlab.gateway.service.TransferService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Service-to-service API used by the control service; not exposed outside the cluster. */
@RestController
@RequestMapping("/internal/runs/{runId}")
class RunController(private val service: TransferService) {

    @GetMapping("/summary")
    fun summary(@PathVariable runId: UUID): RunSummary = service.summarize(runId)

    @DeleteMapping
    fun delete(@PathVariable runId: UUID): ResponseEntity<Void> =
        if (service.deleteRun(runId)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
}
