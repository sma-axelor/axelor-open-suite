/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2024 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.account.service.payment.invoice.payment;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.InvoiceTerm;
import com.axelor.apps.account.db.InvoiceTermPayment;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.PayVoucherElementToPay;
import com.axelor.apps.account.db.Reconcile;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.CurrencyScaleServiceAccount;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.CurrencyService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;

@RequestScoped
public class InvoiceTermPaymentServiceImpl implements InvoiceTermPaymentService {

  protected CurrencyService currencyService;
  protected InvoiceTermService invoiceTermService;
  protected AppAccountService appAccountService;
  protected CurrencyScaleServiceAccount currencyScaleServiceAccount;
  protected InvoicePaymentFinancialDiscountService invoicePaymentFinancialDiscountService;

  @Inject
  public InvoiceTermPaymentServiceImpl(
      CurrencyService currencyService,
      InvoiceTermService invoiceTermService,
      AppAccountService appAccountService,
      CurrencyScaleServiceAccount currencyScaleServiceAccount,
      InvoicePaymentFinancialDiscountService invoicePaymentFinancialDiscountService) {
    this.currencyService = currencyService;
    this.invoiceTermService = invoiceTermService;
    this.appAccountService = appAccountService;
    this.currencyScaleServiceAccount = currencyScaleServiceAccount;
    this.invoicePaymentFinancialDiscountService = invoicePaymentFinancialDiscountService;
  }

  @Override
  public InvoicePayment initInvoiceTermPayments(
      InvoicePayment invoicePayment, List<InvoiceTerm> invoiceTermsToPay) {
    invoicePayment.clearInvoiceTermPaymentList();

    if (CollectionUtils.isEmpty(invoiceTermsToPay)) {
      return invoicePayment;
    }

    boolean isCompanyCurrency =
        invoicePayment.getCurrency().equals(invoiceTermsToPay.get(0).getCompany().getCurrency());
    for (InvoiceTerm invoiceTerm : invoiceTermsToPay) {
      invoicePayment.addInvoiceTermPaymentListItem(
          createInvoiceTermPayment(
              invoicePayment,
              invoiceTerm,
              isCompanyCurrency
                  ? currencyScaleServiceAccount.getCompanyScaledValue(
                      invoiceTerm, invoiceTerm.getCompanyAmountRemaining())
                  : currencyScaleServiceAccount.getScaledValue(
                      invoiceTerm, invoiceTerm.getAmountRemaining())));
    }

    return invoicePayment;
  }

  @Override
  public void createInvoicePaymentTerms(
      InvoicePayment invoicePayment, List<InvoiceTerm> invoiceTermToPayList)
      throws AxelorException {

    Invoice invoice = invoicePayment.getInvoice();
    if (invoice == null
        || CollectionUtils.isEmpty(invoicePayment.getInvoice().getInvoiceTermList())) {
      return;
    }

    List<InvoiceTerm> invoiceTerms;
    if (CollectionUtils.isNotEmpty(invoiceTermToPayList)) {
      invoiceTerms = new ArrayList<>(invoiceTermToPayList);
    } else if (invoicePayment.getMove() != null
        && invoicePayment.getMove().getPaymentVoucher() != null
        && CollectionUtils.isNotEmpty(
            invoicePayment.getMove().getPaymentVoucher().getPayVoucherElementToPayList())) {
      invoiceTerms =
          invoicePayment.getMove().getPaymentVoucher().getPayVoucherElementToPayList().stream()
              .sorted(Comparator.comparing(PayVoucherElementToPay::getSequence))
              .map(PayVoucherElementToPay::getInvoiceTerm)
              .collect(Collectors.toList());
    } else {
      invoiceTerms = invoiceTermService.getUnpaidInvoiceTermsFiltered(invoice);
    }

    if (CollectionUtils.isNotEmpty(invoiceTerms)) {
      this.initInvoiceTermPaymentsWithAmount(
          invoicePayment, invoiceTerms, invoicePayment.getAmount(), invoicePayment.getAmount());
    }
  }

  @Override
  public List<InvoiceTermPayment> initInvoiceTermPaymentsWithAmount(
      InvoicePayment invoicePayment,
      List<InvoiceTerm> invoiceTermsToPay,
      BigDecimal availableAmount,
      BigDecimal reconcileAmount) {
    List<InvoiceTermPayment> invoiceTermPaymentList = new ArrayList<>();
    InvoiceTerm invoiceTermToPay;
    InvoiceTermPayment invoiceTermPayment;
    BigDecimal baseAvailableAmount = availableAmount;
    BigDecimal availableAmountUnchanged = availableAmount;
    int invoiceTermCount = invoiceTermsToPay.size();
    boolean isCompanyCurrency;

    if (invoicePayment != null) {
      invoicePayment.clearInvoiceTermPaymentList();
    }

    int i = 0;
    while (i < invoiceTermCount && availableAmount.signum() > 0) {
      invoiceTermToPay =
          this.getInvoiceTermToPay(
              invoicePayment, invoiceTermsToPay, availableAmount, i++, invoiceTermCount);

      isCompanyCurrency =
          invoiceTermToPay.getAmount().compareTo(invoiceTermToPay.getCompanyAmount()) == 0;

      BigDecimal invoiceTermAmount =
          isCompanyCurrency
              ? currencyScaleServiceAccount.getCompanyScaledValue(
                  invoiceTermToPay, invoiceTermToPay.getAmountRemaining())
              : currencyScaleServiceAccount.getScaledValue(
                  invoiceTermToPay, invoiceTermToPay.getAmountRemaining());

      if (invoiceTermAmount.compareTo(availableAmount) >= 0) {
        invoiceTermPayment =
            createInvoiceTermPayment(invoicePayment, invoiceTermToPay, availableAmount);
        availableAmount = BigDecimal.ZERO;
      } else {
        invoiceTermPayment =
            createInvoiceTermPayment(invoicePayment, invoiceTermToPay, invoiceTermAmount);
        availableAmount = availableAmount.subtract(invoiceTermAmount);
      }

      invoiceTermPaymentList.add(invoiceTermPayment);

      if (invoicePayment != null) {
        invoicePayment.addInvoiceTermPaymentListItem(invoiceTermPayment);

        if (invoicePayment.getApplyFinancialDiscount() && !invoicePayment.getManualChange()) {
          BigDecimal previousAmount =
              invoicePayment.getAmount().add(invoicePayment.getFinancialDiscountTotalAmount());
          invoicePaymentFinancialDiscountService.computeFinancialDiscount(invoicePayment);
          availableAmount =
              baseAvailableAmount.subtract(this.getCurrentInvoicePaymentAmount(invoicePayment));
          invoicePayment.setAmount(
              currencyScaleServiceAccount.getCompanyScaledValue(
                  invoiceTermToPay,
                  previousAmount.subtract(invoicePayment.getFinancialDiscountTotalAmount())));
          invoicePayment.setTotalAmountWithFinancialDiscount(
              currencyScaleServiceAccount.getCompanyScaledValue(
                  invoiceTermToPay,
                  invoicePayment
                      .getAmount()
                      .add(invoicePayment.getFinancialDiscountTotalAmount())));
        }
      }

      if (availableAmountUnchanged.compareTo(reconcileAmount) != 0
          && availableAmount.signum() <= 0) {
        BigDecimal totalInCompanyCurrency =
            invoiceTermPaymentList.stream()
                .map(InvoiceTermPayment::getCompanyPaidAmount)
                .reduce(BigDecimal::add)
                .orElse(BigDecimal.ZERO);
        BigDecimal diff = reconcileAmount.subtract(totalInCompanyCurrency);
        BigDecimal companyPaidAmount =
            currencyScaleServiceAccount.getCompanyScaledValue(
                invoiceTermToPay, invoiceTermPayment.getCompanyPaidAmount().add(diff));

        invoiceTermPayment.setCompanyPaidAmount(companyPaidAmount);
      }
    }

    return invoiceTermPaymentList;
  }

  protected BigDecimal getCurrentInvoicePaymentAmount(InvoicePayment invoicePayment) {
    return invoicePayment.getInvoiceTermPaymentList().stream()
        .map(it -> it.getPaidAmount().add(it.getFinancialDiscountAmount()))
        .reduce(BigDecimal::add)
        .orElse(BigDecimal.ZERO);
  }

  protected InvoiceTerm getInvoiceTermToPay(
      InvoicePayment invoicePayment,
      List<InvoiceTerm> invoiceTermsToPay,
      BigDecimal amount,
      int counter,
      int size) {
    if (invoicePayment != null) {
      return invoiceTermsToPay.get(counter);
    } else {
      return invoiceTermsToPay.subList(counter, size).stream()
          .filter(
              it ->
                  it.getAmount().compareTo(amount) == 0
                      || it.getAmountRemaining().compareTo(amount) == 0)
          .findAny()
          .orElse(invoiceTermsToPay.get(counter));
    }
  }

  @Override
  public InvoiceTermPayment createInvoiceTermPayment(
      InvoicePayment invoicePayment, InvoiceTerm invoiceTermToPay, BigDecimal paidAmount) {
    if (invoicePayment == null) {
      return this.initInvoiceTermPayment(invoiceTermToPay, paidAmount);
    } else {
      this.toggleFinancialDiscount(invoicePayment, invoiceTermToPay);
      return this.initInvoiceTermPayment(
          invoicePayment, invoiceTermToPay, paidAmount, invoicePayment.getApplyFinancialDiscount());
    }
  }

  protected void toggleFinancialDiscount(InvoicePayment invoicePayment, InvoiceTerm invoiceTerm) {
    boolean isLinkedToPayment = true;
    if (invoicePayment.getReconcile() != null) {
      Reconcile reconcile = invoicePayment.getReconcile();
      isLinkedToPayment =
          reconcile.getDebitMoveLine().getMove().getFunctionalOriginSelect()
                  == MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT
              || reconcile.getCreditMoveLine().getMove().getFunctionalOriginSelect()
                  == MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT;
    }
    if (!invoicePayment.getApplyFinancialDiscount()
        && !invoicePayment.getManualChange()
        && Optional.of(invoicePayment)
            .map(InvoicePayment::getMove)
            .map(Move::getPaymentVoucher)
            .isEmpty()
        && (!invoiceTerm.getIsSelectedOnPaymentSession()
            || invoiceTerm.getApplyFinancialDiscountOnPaymentSession())
        && !invoiceTermService.isPartiallyPaid(invoiceTerm)) {
      invoicePayment.setApplyFinancialDiscount(
          invoiceTerm.getFinancialDiscountDeadlineDate() != null
              && invoiceTerm.getApplyFinancialDiscount()
              && !invoicePayment
                  .getPaymentDate()
                  .isAfter(invoiceTerm.getFinancialDiscountDeadlineDate())
              && isLinkedToPayment);
    }
  }

  protected InvoiceTermPayment initInvoiceTermPayment(
      InvoiceTerm invoiceTermToPay, BigDecimal amount) {
    return initInvoiceTermPayment(
        null, invoiceTermToPay, amount, invoiceTermToPay.getApplyFinancialDiscount());
  }

  protected InvoiceTermPayment initInvoiceTermPayment(
      InvoicePayment invoicePayment,
      InvoiceTerm invoiceTermToPay,
      BigDecimal paidAmount,
      boolean applyFinancialDiscount) {
    InvoiceTermPayment invoiceTermPayment = new InvoiceTermPayment();

    invoiceTermPayment.setInvoicePayment(invoicePayment);
    invoiceTermPayment.setInvoiceTerm(invoiceTermToPay);

    boolean isCompanyCurrency =
        invoiceTermToPay.getAmount().compareTo(invoiceTermToPay.getCompanyAmount()) == 0;

    invoiceTermPayment.setPaidAmount(paidAmount);

    if (paidAmount.compareTo(invoiceTermToPay.getAmount()) == 0) {
      manageInvoiceTermFinancialDiscount(
          invoiceTermPayment, invoiceTermToPay, applyFinancialDiscount);
    }

    invoiceTermPayment.setCompanyPaidAmount(
        isCompanyCurrency
            ? paidAmount
            : this.computeCompanyPaidAmount(invoiceTermToPay, paidAmount));

    return invoiceTermPayment;
  }

  protected BigDecimal computeCompanyPaidAmount(InvoiceTerm invoiceTerm, BigDecimal paidAmount) {
    BigDecimal ratio =
        invoiceTerm
            .getCompanyAmount()
            .divide(
                invoiceTerm.getAmount(), AppBaseService.COMPUTATION_SCALING, RoundingMode.HALF_UP);

    return currencyScaleServiceAccount.getCompanyScaledValue(
        invoiceTerm, paidAmount.multiply(ratio));
  }

  protected BigDecimal computePaidAmount(InvoiceTerm invoiceTerm, BigDecimal companyPaidAmount) {
    BigDecimal ratio =
        invoiceTerm
            .getAmount()
            .divide(
                invoiceTerm.getCompanyAmount(),
                AppBaseService.COMPUTATION_SCALING,
                RoundingMode.HALF_UP);

    return currencyScaleServiceAccount.getScaledValue(
        invoiceTerm, companyPaidAmount.multiply(ratio));
  }

  @Override
  public void manageInvoiceTermFinancialDiscount(
      InvoiceTermPayment invoiceTermPayment,
      InvoiceTerm invoiceTerm,
      boolean applyFinancialDiscount) {
    if (applyFinancialDiscount && invoiceTerm.getAmountRemainingAfterFinDiscount().signum() > 0) {
      invoiceTermPayment.setPaidAmount(
          currencyScaleServiceAccount.getScaledValue(
              invoiceTerm,
              invoiceTermPayment
                  .getPaidAmount()
                  .add(invoiceTermPayment.getFinancialDiscountAmount())));

      BigDecimal ratioPaid =
          invoiceTermPayment
              .getPaidAmount()
              .divide(
                  invoiceTerm.getAmount(),
                  AppBaseService.COMPUTATION_SCALING,
                  RoundingMode.HALF_UP);

      invoiceTermPayment.setFinancialDiscountAmount(
          currencyScaleServiceAccount.getScaledValue(
              invoiceTerm, invoiceTerm.getFinancialDiscountAmount().multiply(ratioPaid)));

      invoiceTermPayment.setPaidAmount(
          currencyScaleServiceAccount.getScaledValue(
              invoiceTerm,
              invoiceTermPayment
                  .getPaidAmount()
                  .subtract(invoiceTermPayment.getFinancialDiscountAmount())));
    }
  }

  @Override
  public InvoicePayment updateInvoicePaymentAmount(InvoicePayment invoicePayment)
      throws AxelorException {

    invoicePayment.setAmount(
        computeInvoicePaymentAmount(invoicePayment, invoicePayment.getInvoiceTermPaymentList()));

    return invoicePayment;
  }

  @Override
  public BigDecimal computeInvoicePaymentAmount(
      InvoicePayment invoicePayment, List<InvoiceTermPayment> invoiceTermPayments)
      throws AxelorException {

    BigDecimal sum =
        invoicePayment.getInvoiceTermPaymentList().stream()
            .map(it -> it.getPaidAmount().add(it.getFinancialDiscountAmount()))
            .reduce(BigDecimal::add)
            .orElse(BigDecimal.ZERO);

    sum =
        currencyScaleServiceAccount.getScaledValue(
            invoicePayment,
            currencyService.getAmountCurrencyConvertedAtDate(
                invoicePayment.getInvoice().getCurrency(),
                invoicePayment.getCurrency(),
                sum,
                appAccountService.getTodayDate(invoicePayment.getInvoice().getCompany())));

    return sum;
  }
}
