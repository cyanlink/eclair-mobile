/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.wallet.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import fr.acinq.bitcoin.MilliSatoshi;
import fr.acinq.eclair.CoinUtils;
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet;
import fr.acinq.eclair.payment.PaymentRequest;
import fr.acinq.eclair.wallet.App;
import fr.acinq.eclair.wallet.R;
import fr.acinq.eclair.wallet.actors.NodeSupervisor;
import fr.acinq.eclair.wallet.databinding.FragmentReceivePaymentBinding;
import fr.acinq.eclair.wallet.models.Payment;
import fr.acinq.eclair.wallet.models.PaymentDirection;
import fr.acinq.eclair.wallet.models.PaymentStatus;
import fr.acinq.eclair.wallet.models.PaymentType;
import fr.acinq.eclair.wallet.tasks.LightningQRCodeTask;
import fr.acinq.eclair.wallet.tasks.QRCodeTask;
import fr.acinq.eclair.wallet.utils.Constants;
import fr.acinq.eclair.wallet.utils.WalletUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.util.Date;
import java.util.Objects;

public class ReceivePaymentFragment extends Fragment implements QRCodeTask.AsyncQRCodeResponse, LightningQRCodeTask.AsyncQRCodeResponse, PaymentRequestParametersDialog.PaymentRequestParametersDialogCallback {
  private final Logger log = LoggerFactory.getLogger(ReceivePaymentFragment.class);
  private FragmentReceivePaymentBinding mBinding;

  private boolean isGeneratingPaymentRequest = false;

  private PaymentRequestParametersDialog mPRParamsDialog;

  private boolean lightningUseDefaultDescription = true;
  private String lightningPaymentRequest = null;
  private String lightningDescription = "";
  private Option<MilliSatoshi> lightningAmount = Option.apply(null);

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setHasOptionsMenu(false);
  }

  @Override
  public View onCreateView(final LayoutInflater inflater, final ViewGroup container, Bundle savedInstanceState) {
    mBinding = DataBindingUtil.inflate(inflater, R.layout.fragment_receive_payment, container, false);
    mBinding.setPaymentType(0);
    mBinding.pickOnchainButton.setOnClickListener(v -> mBinding.setPaymentType(0));
    mBinding.pickLightningButton.setOnClickListener(v -> {
      generatePaymentRequest();
      mBinding.setPaymentType(1);
    });
    mBinding.lightningParameters.setOnClickListener(v -> {
      if (!isGeneratingPaymentRequest) {
        mPRParamsDialog = new PaymentRequestParametersDialog(ReceivePaymentFragment.this.getContext(), ReceivePaymentFragment.this,
          R.style.CustomAlertDialog, this.lightningDescription, this.lightningAmount);
        mPRParamsDialog.show();
      }
    });
    return mBinding.getRoot();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this);
    }
    final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
    mBinding.setIsLightningInboundEnabled(prefs.getBoolean(Constants.SETTING_ENABLE_LIGHTNING_INBOUND_PAYMENTS, false));
    mBinding.setHasNormalChannels(NodeSupervisor.hasActiveChannels());
    if (mBinding.getIsLightningInboundEnabled() && mBinding.getPaymentType() == 1 && !isGeneratingPaymentRequest && lightningPaymentRequest == null) {
      generatePaymentRequest();
    }
    setOnchainAddress();
  }

  @Override
  public void onPause() {
    EventBus.getDefault().unregister(this);
    if (mPRParamsDialog != null) {
      mPRParamsDialog.dismiss();
    }
    super.onPause();
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void handleNewWalletAddress(final ElectrumWallet.NewWalletReceiveAddress addressEvent) {
    setOnchainAddress();
  }

  private void generatePaymentRequest() {
    final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
    if (prefs.getBoolean(Constants.SETTING_ENABLE_LIGHTNING_INBOUND_PAYMENTS, false) && !isGeneratingPaymentRequest) {
      isGeneratingPaymentRequest = true;
      mBinding.setIsGeneratingLightningPR(isGeneratingPaymentRequest);
      if (lightningUseDefaultDescription) {
        lightningDescription = prefs.getString(Constants.SETTING_PAYMENT_REQUEST_DEFAULT_DESCRIPTION, "");
      }
      loadingPaymentRequestFields();
      log.debug("starting to generate payment request...");
      AsyncTask.execute(() -> {
        try {
          final PaymentRequest paymentRequest = Objects.requireNonNull(getApp())
            .generatePaymentRequest(lightningDescription, lightningAmount, Long.parseLong(Objects.requireNonNull(prefs.getString(Constants.SETTING_PAYMENT_REQUEST_EXPIRY, "3600"))));
          log.debug("successfully generated payment_request, starting processing");
          final String paymentRequestStr = PaymentRequest.write(paymentRequest);
          final String description = paymentRequest.description().isLeft() ? paymentRequest.description().left().get() : paymentRequest.description().right().get().toString();
          log.debug("payment request serialized to=" + paymentRequestStr);

          if (getApp() != null && getApp().getDBHelper() != null) {
            final Payment newPayment = new Payment();
            newPayment.setType(PaymentType.BTC_LN);
            newPayment.setDirection(PaymentDirection.RECEIVED);
            newPayment.setReference(paymentRequest.paymentHash().toString());
            newPayment.setAmountRequestedMsat(WalletUtils.getLongAmountFromInvoice(paymentRequest));
            newPayment.setRecipient(paymentRequest.nodeId().toString());
            newPayment.setPaymentRequest(paymentRequestStr.toLowerCase());
            newPayment.setStatus(PaymentStatus.INIT);
            newPayment.setDescription(description);
            newPayment.setUpdated(new Date());
            getApp().getDBHelper().insertOrUpdatePayment(newPayment);
          }

          this.lightningPaymentRequest = paymentRequestStr;
          this.lightningDescription = description;
          this.lightningAmount = paymentRequest.amount();

          if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
              updateLightningDescriptionView();
              updateLightningAmountView();
              mBinding.lightningPr.setText(paymentRequestStr);
              mBinding.lightningPr.setOnClickListener(v -> copyReceptionAddress(paymentRequestStr));
              mBinding.lightningQr.setOnClickListener(v -> copyReceptionAddress(paymentRequestStr));
            });
          }

          new LightningQRCodeTask(this, paymentRequestStr, 700, 700).execute();
        } catch (Exception e) {
          failPaymentRequestFields();
          log.error("could not generate payment request", e);
        } finally {
          isGeneratingPaymentRequest = false;
          mBinding.setIsGeneratingLightningPR(isGeneratingPaymentRequest);
          log.info("end of payment request generation method...");
        }
      });
    }
  }

  private void setOnchainAddress() {
    final String address = getApp() != null ? getApp().getWalletAddress() : getString(R.string.unknown);
    mBinding.onchainAddress.setText(address);
    mBinding.onchainAddress.setOnClickListener(v -> copyReceptionAddress(address));
    mBinding.onchainQr.setOnClickListener(v -> copyReceptionAddress(address));
    new QRCodeTask(this, address, 700, 700).execute();
  }

  @Override
  public void processFinish(final Bitmap output) {
    if (output != null) {
      mBinding.onchainQr.setImageBitmap(output);
    }
  }

  private App getApp() {
    return (getActivity() != null && getActivity().getApplication() != null) ? (App) getActivity().getApplication() : null;
  }

  private void copyReceptionAddress(final String address) {
    if (getActivity() == null) return;
    try {
      ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin payment request", address));
      Toast.makeText(getActivity().getApplicationContext(), "Copied to clipboard!", Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
      log.error("failed to copy with cause=" + e.getMessage());
    }
  }

  @Override
  public void processLightningQRCodeFinish(Bitmap output) {
    if (output != null) {
      mBinding.lightningQr.setImageBitmap(output);
    }
  }

  private void loadingPaymentRequestFields() {
    mBinding.lightningPr.setText(R.string.receivepayment_lightning_wait);
    mBinding.lightningQr.setImageDrawable(getResources().getDrawable(R.drawable.qrcode_placeholder));
  }

  private void failPaymentRequestFields() {
    mBinding.lightningPr.setText(R.string.receivepayment_lightning_error);
    this.lightningPaymentRequest = null;
    this.lightningDescription = "";
    this.lightningAmount = Option.apply(null);
    updateLightningDescriptionView();
    updateLightningAmountView();
    mBinding.lightningQr.setImageDrawable(getResources().getDrawable(R.drawable.qrcode_placeholder));
  }

  @Override
  public void onConfirm(PaymentRequestParametersDialog dialog, String description, Option<MilliSatoshi> amount) {
    this.lightningDescription = description;
    this.lightningAmount = amount;
    this.lightningUseDefaultDescription = false; // value from dialog always overrides default value
    dialog.dismiss();
    generatePaymentRequest();
  }

  private void updateLightningDescriptionView() {
    if (this.lightningDescription == null || this.lightningDescription.length() == 0) {
      mBinding.lightningDescription.setText(getString(R.string.receivepayment_lightning_description_notset));
      if (getContext() != null) {
        mBinding.lightningDescription.setTextColor(ContextCompat.getColor(getContext(), R.color.grey_2));
        mBinding.lightningDescription.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
      }
    } else {
      mBinding.lightningDescription.setText(this.lightningDescription);
      if (getContext() != null) {
        mBinding.lightningDescription.setTextColor(ContextCompat.getColor(getContext(), R.color.grey_4));
        mBinding.lightningDescription.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
      }
    }
  }

  private void updateLightningAmountView() {
    if (this.lightningAmount == null || this.lightningAmount.isEmpty()) {
      mBinding.lightningAmount.setText(getString(R.string.receivepayment_lightning_amount_notset));
      if (getContext() != null) {
        mBinding.lightningAmount.setTextColor(ContextCompat.getColor(getContext(), R.color.grey_2));
        mBinding.lightningAmount.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
      }
    } else {
      final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.getContext());
      mBinding.lightningAmount.setText(Html.fromHtml(getString(R.string.receivepayment_lightning_amount_value,
        CoinUtils.formatAmountInUnit(this.lightningAmount.get(), WalletUtils.getPreferredCoinUnit(prefs), true),
        WalletUtils.convertMsatToFiatWithUnit(this.lightningAmount.get().amount(), WalletUtils.getPreferredFiat(prefs)))));
      if (getContext() != null) {
        mBinding.lightningAmount.setTextColor(ContextCompat.getColor(this.getContext(), R.color.grey_4));
        mBinding.lightningAmount.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
      }
    }
  }

  public void notifyChannelsUpdate() {
    if (mBinding != null) {
      mBinding.setHasNormalChannels(NodeSupervisor.hasActiveChannels());
    }
  }
}
