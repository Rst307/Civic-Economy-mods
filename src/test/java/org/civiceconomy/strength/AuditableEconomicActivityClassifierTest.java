package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentKind;
import org.civiceconomy.fiscal.PaymentTransaction;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.TransactionState;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class AuditableEconomicActivityClassifierTest {
    @Test
    void includesCommittedUnrefundedPublicProjectWithDistinctControllers() {
        UUID transactionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        PaymentTransaction transaction = new PaymentTransaction(
                transactionId,
                new ServiceIdentity("public-projects"),
                "project-payment-1",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                new AccountId("nation:33333333-3333-3333-3333-333333333333:treasury"),
                new AccountId("organization:rail-builder:fiscal"),
                MoneyAmount.ofMinorUnits(100L),
                PaymentKind.PAYMENT,
                Optional.empty(),
                MoneyAmount.ZERO,
                Optional.of("Verified rail construction milestone"),
                TransactionState.CIVIC_COMMITTED);
        AuditableEconomicActivityEvidence evidence = new AuditableEconomicActivityEvidence(
                new NationId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                transaction,
                AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                "project:rail-corridor-1",
                "controller:nation-3333",
                "controller:rail-builder",
                MoneyAmount.ofMinorUnits(100L),
                "budget:rail-2026");

        AuditableEconomicActivityAssessment assessment =
                new AuditableEconomicActivityClassifier().assess(evidence);

        assertEquals(AuditableEconomicActivityDecision.INCLUDED, assessment.decision());
        assertEquals(transactionId, assessment.transactionId());
        assertEquals(100L, assessment.includedValue().minorUnits());
        assertEquals("project:rail-corridor-1", assessment.subjectReference());
    }

    @Test
    void excludesRefundedPaymentWithZeroIncludedValueAndExplicitReason() {
        PaymentTransaction refunded = new PaymentTransaction(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                new ServiceIdentity("public-projects"),
                "refunded-project-payment",
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                new AccountId("nation:33333333-3333-3333-3333-333333333333:treasury"),
                new AccountId("organization:rail-builder:fiscal"),
                MoneyAmount.ofMinorUnits(100L),
                PaymentKind.PAYMENT,
                Optional.empty(),
                MoneyAmount.ofMinorUnits(100L),
                Optional.of("Refunded rail milestone"),
                TransactionState.CIVIC_COMMITTED);
        AuditableEconomicActivityEvidence evidence = new AuditableEconomicActivityEvidence(
                new NationId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                refunded,
                AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                "project:rail-corridor-refunded",
                "controller:nation-3333",
                "controller:rail-builder",
                MoneyAmount.ofMinorUnits(100L),
                "refund:44444444-4444-4444-4444-444444444444");

        AuditableEconomicActivityAssessment assessment =
                new AuditableEconomicActivityClassifier().assess(evidence);

        assertEquals(
                AuditableEconomicActivityDecision.EXCLUDED_REFUNDED,
                assessment.decision());
        assertEquals(0L, assessment.includedValue().minorUnits());
    }

    @Test
    void excludesSameControllerPaymentAsObviousSelfCirculation() {
        PaymentTransaction transaction = committedPayment(
                UUID.fromString("66666666-6666-6666-6666-666666666666"));
        AuditableEconomicActivityEvidence evidence = new AuditableEconomicActivityEvidence(
                new NationId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                transaction,
                AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                "project:self-controlled",
                "controller:same-owner",
                "controller:same-owner",
                MoneyAmount.ofMinorUnits(100L),
                "control:same-owner");

        AuditableEconomicActivityAssessment assessment =
                new AuditableEconomicActivityClassifier().assess(evidence);

        assertEquals(
                AuditableEconomicActivityDecision.EXCLUDED_COMMON_CONTROL,
                assessment.decision());
        assertEquals(0L, assessment.includedValue().minorUnits());
    }

    @Test
    void excludesPaymentThatHasNotReachedCivicCommit() {
        PaymentTransaction prepared = new PaymentTransaction(
                UUID.fromString("88888888-8888-8888-8888-888888888888"),
                new ServiceIdentity("public-projects"),
                "prepared-project-payment",
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                new AccountId("nation:33333333-3333-3333-3333-333333333333:treasury"),
                new AccountId("organization:contractor:fiscal"),
                MoneyAmount.ofMinorUnits(100L),
                PaymentKind.PAYMENT,
                Optional.empty(),
                MoneyAmount.ZERO,
                Optional.of("Prepared public project"),
                TransactionState.PREPARED);
        AuditableEconomicActivityEvidence evidence = new AuditableEconomicActivityEvidence(
                new NationId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                prepared,
                AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                "project:not-committed",
                "controller:nation-3333",
                "controller:contractor",
                MoneyAmount.ofMinorUnits(100L),
                "payment:prepared");

        AuditableEconomicActivityAssessment assessment =
                new AuditableEconomicActivityClassifier().assess(evidence);

        assertEquals(
                AuditableEconomicActivityDecision.EXCLUDED_NOT_COMMITTED,
                assessment.decision());
        assertEquals(0L, assessment.includedValue().minorUnits());
    }

    private static PaymentTransaction committedPayment(UUID transactionId) {
        return new PaymentTransaction(
                transactionId,
                new ServiceIdentity("public-projects"),
                "project-" + transactionId,
                UUID.fromString("77777777-7777-7777-7777-777777777777"),
                new AccountId("nation:33333333-3333-3333-3333-333333333333:treasury"),
                new AccountId("organization:contractor:fiscal"),
                MoneyAmount.ofMinorUnits(100L),
                PaymentKind.PAYMENT,
                Optional.empty(),
                MoneyAmount.ZERO,
                Optional.of("Verified public project"),
                TransactionState.CIVIC_COMMITTED);
    }
}
