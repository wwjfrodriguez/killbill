/*
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.beatrix.integration.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountData;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.beatrix.integration.TestIntegrationBase;
import org.killbill.billing.beatrix.util.InvoiceChecker.ExpectedInvoiceItemCheck;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.DefaultEntitlement;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.mock.MockAccountBuilder;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.usage.api.SubscriptionUsageRecord;
import org.killbill.billing.usage.api.UnitUsageRecord;
import org.killbill.billing.usage.api.UsageApiException;
import org.killbill.billing.usage.api.UsageRecord;
import org.killbill.billing.util.callcontext.CallContext;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

public class TestConsumableInArrear extends TestIntegrationBase {

    @Test(groups = "slow")
    public void testWithNoUsageInPeriodAndOldUsage() throws Exception {
        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE, NextEvent.BLOCK NextEvent.INVOICE
        //
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.ANNUAL, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        // Check bundle after BP got created otherwise we get an error from auditApi.
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));

        //
        // ADD ADD_ON ON THE SAME DAY
        //
        final DefaultEntitlement aoSubscription = addAOEntitlementAndCheckForCompletion(bpSubscription.getBundleId(), "Bullets", ProductCategory.ADD_ON, BillingPeriod.NO_BILLING_PERIOD, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.NULL_INVOICE);

        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 4, 1), 99L, callContext);
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 4, 15), 100L, callContext);

        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.NULL_INVOICE, NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDays(30);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 5, 1), InvoiceItemType.USAGE, new BigDecimal("5.90")));

        // We don't expect any invoice
        busHandler.pushExpectedEvents(NextEvent.NULL_INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 6, 1), 50L, callContext);
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 6, 16), 300L, callContext);

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.USAGE, new BigDecimal("11.80")));

        // Should be ignored because this is outside of optimization range (org.killbill.invoice.readMaxRawUsagePreviousPeriod = 2) => we will only look for items > 2012-7-1 - 2 months = 2012-5-1
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 4, 30), 100L, callContext);

        // Should be invoiced from past period
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 5, 1), 199L, callContext);

        // New usage for this past period
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 7, 1), 50L, callContext);
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 7, 16), 300L, callContext);

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 4, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 6, 1), InvoiceItemType.USAGE, new BigDecimal("5.90")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 1), new LocalDate(2012, 8, 1), InvoiceItemType.USAGE, new BigDecimal("11.80")));


        // Add a few more month of usage data and check correctness of invoice: iterate 8 times until 2013-4-1 (prior ANNUAL renewal)
        LocalDate startDate = new LocalDate(2012, 8, 1);
        int currentInvoice = 5;
        for (int i = 0; i < 8; i++) {

            setUsage(aoSubscription.getId(), "bullets", startDate.plusDays(15), 350L, callContext);

            busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
            clock.addMonths(1);
            assertListenerStatus();

            invoiceChecker.checkInvoice(account.getId(), currentInvoice, callContext,
                                        new ExpectedInvoiceItemCheck(startDate, startDate.plusMonths(1), InvoiceItemType.USAGE, new BigDecimal("11.80")));


            startDate = startDate.plusMonths(1);
            currentInvoice++;
        }
    }

    @Test(groups = "slow")
    public void testWithCancellation() throws Exception {
        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE, NextEvent.BLOCK NextEvent.INVOICE
        //
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.ANNUAL, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        // Check bundle after BP got created otherwise we get an error from auditApi.
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));

        //
        // ADD ADD_ON ON THE SAME DAY
        //
        final DefaultEntitlement aoSubscription = addAOEntitlementAndCheckForCompletion(bpSubscription.getBundleId(), "Bullets", ProductCategory.ADD_ON, BillingPeriod.NO_BILLING_PERIOD, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.NULL_INVOICE);

        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 4, 1), 99L, callContext);
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 4, 15), 100L, callContext);

        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.NULL_INVOICE, NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDays(30);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2013, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 5, 1), InvoiceItemType.USAGE, new BigDecimal("5.90")));

        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 5, 3), 99L, callContext);
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 5, 5), 100L, callContext);

        // This one should be ignored
        setUsage(aoSubscription.getId(), "bullets", new LocalDate(2012, 5, 29), 100L, callContext);

        clock.addDays(27);
        busHandler.pushExpectedEvents(NextEvent.BLOCK, NextEvent.CANCEL, NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        aoSubscription.cancelEntitlementWithDateOverrideBillingPolicy(new LocalDate(2012, 5, 28), BillingActionPolicy.IMMEDIATE, ImmutableList.<PluginProperty>of(), callContext);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 3, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 1), new LocalDate(2012, 5, 28), InvoiceItemType.USAGE, new BigDecimal("5.90")));

        busHandler.pushExpectedEvent(NextEvent.NULL_INVOICE);
        clock.addDays(4);
        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testWithNoRecurringPlan() throws Exception {
        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        // Create subscription
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Trebuchet", ProductCategory.BASE, BillingPeriod.NO_BILLING_PERIOD, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.NULL_INVOICE);
        // Check bundle after BP got created otherwise we get an error from auditApi.
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), internalCallContext);

        Assert.assertNull(bpSubscription.getSubscriptionBase().getChargedThroughDate());

        // Record usage for first month
        setUsage(bpSubscription.getId(), "stones", new LocalDate(2012, 4, 5), 85L, callContext);
        setUsage(bpSubscription.getId(), "stones", new LocalDate(2012, 4, 15), 150L, callContext);

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 1, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 5, 1), InvoiceItemType.USAGE, new BigDecimal("1000")));

        final DateTime firstExpectedCTD = account.getReferenceTime().withMonthOfYear(5).withDayOfMonth(1);
        Assert.assertEquals(subscriptionBaseInternalApiApi.getSubscriptionFromId(bpSubscription.getId(), internalCallContext).getChargedThroughDate().compareTo(firstExpectedCTD), 0);

        // No usage in second month
        busHandler.pushExpectedEvents(NextEvent.NULL_INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        Assert.assertEquals(subscriptionBaseInternalApiApi.getSubscriptionFromId(bpSubscription.getId(), internalCallContext).getChargedThroughDate().compareTo(firstExpectedCTD), 0);

        // Record usage for third month (verify invoicing resumes)
        setUsage(bpSubscription.getId(), "stones", new LocalDate(2012, 6, 5), 25L, callContext);
        setUsage(bpSubscription.getId(), "stones", new LocalDate(2012, 6, 15), 50L, callContext);

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();

        invoiceChecker.checkInvoice(account.getId(), 2, callContext,
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 1), new LocalDate(2012, 7, 1), InvoiceItemType.USAGE, new BigDecimal("100")));

        final DateTime secondExpectedCTD = account.getReferenceTime().withMonthOfYear(7).withDayOfMonth(1);
        Assert.assertEquals(subscriptionBaseInternalApiApi.getSubscriptionFromId(bpSubscription.getId(), internalCallContext).getChargedThroughDate().compareTo(secondExpectedCTD), 0);
    }

    private void setUsage(final UUID subscriptionId, final String unitType, final LocalDate startDate, final Long amount, final CallContext context) throws UsageApiException {
        final List<UsageRecord> usageRecords = new ArrayList<UsageRecord>();
        usageRecords.add(new UsageRecord(startDate, amount));
        final List<UnitUsageRecord> unitUsageRecords = new ArrayList<UnitUsageRecord>();
        unitUsageRecords.add(new UnitUsageRecord(unitType, usageRecords));
        final SubscriptionUsageRecord record = new SubscriptionUsageRecord(subscriptionId, UUID.randomUUID().toString(), unitUsageRecords);
        usageUserApi.recordRolledUpUsage(record, context);
    }
}
