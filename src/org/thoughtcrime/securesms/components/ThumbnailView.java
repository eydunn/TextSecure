package org.thoughtcrime.securesms.components;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.DrawableRequestBuilder;
import com.bumptech.glide.GenericRequestBuilder;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.GlideBitmapDrawable;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri;
import org.thoughtcrime.securesms.mms.RoundedCorners;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.util.FutureTaskListener;
import org.thoughtcrime.securesms.util.ListenableFutureTask;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.whispersystems.libaxolotl.util.guava.Optional;

public class ThumbnailView extends FrameLayout {
  private static final String TAG = ThumbnailView.class.getSimpleName();

  private ImageView       image;
  private ImageView       removeButton;
  private int             backgroundColorHint;
  private int             radius;
  private OnClickListener parentClickListener;

  private Optional<TransferControlView>   transferControls       = Optional.absent();
  private ListenableFutureTask<SlideDeck> slideDeckFuture        = null;
  private SlideDeckListener               slideDeckListener      = null;
  private ThumbnailClickListener          thumbnailClickListener = null;
  private ThumbnailClickListener          downloadClickListener  = null;
  private Slide                           slide                  = null;

  public ThumbnailView(Context context) {
    this(context, null);
  }

  public ThumbnailView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ThumbnailView(final Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    inflate(context, R.layout.thumbnail_view, this);
    radius = getResources().getDimensionPixelSize(R.dimen.message_bubble_corner_radius);
    image  = (ImageView) findViewById(R.id.thumbnail_image);
    super.setOnClickListener(new ThumbnailClickDispatcher());

    if (attrs != null) {
      TypedArray typedArray = context.getTheme().obtainStyledAttributes(attrs, R.styleable.ThumbnailView, 0, 0);
      backgroundColorHint = typedArray.getColor(0, Color.BLACK);
      typedArray.recycle();
    }
  }

  @Override
  public void setOnClickListener(OnClickListener l) {
    parentClickListener = l;
  }

  @Override
  public void setFocusable(boolean focusable) {
    super.setFocusable(focusable);
    if (transferControls.isPresent()) transferControls.get().setFocusable(focusable);
  }

  @Override
  public void setClickable(boolean clickable) {
    super.setClickable(clickable);
    if (transferControls.isPresent()) transferControls.get().setClickable(clickable);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    if (removeButton != null) {
      final int paddingHorizontal = removeButton.getWidth()  / 2;
      final int paddingVertical   = removeButton.getHeight() / 2;
      image.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, 0);
    }
  }

  private ImageView getRemoveButton() {
    if (removeButton == null) removeButton = ViewUtil.inflateStub(this, R.id.remove_button_stub);
    return removeButton;
  }

  private TransferControlView getTransferControls() {
    if (!transferControls.isPresent()) {
      transferControls = Optional.of((TransferControlView)ViewUtil.inflateStub(this, R.id.transfer_controls_stub));
    }
    return transferControls.get();
  }

  public void setBackgroundColorHint(int color) {
    this.backgroundColorHint = color;
  }

  public void setImageResource(@NonNull MasterSecret masterSecret,
                               @NonNull ListenableFutureTask<SlideDeck> slideDeckFuture,
                               boolean showControls, boolean showRemove)
  {
    if (this.slideDeckFuture != null && this.slideDeckListener != null) {
      this.slideDeckFuture.removeListener(this.slideDeckListener);
    }

    if (!slideDeckFuture.equals(this.slideDeckFuture)) {
      if (transferControls.isPresent()) getTransferControls().clear();
      image.setImageDrawable(null);
      this.slide = null;
    }

    this.slideDeckListener = new SlideDeckListener(masterSecret, showControls, showRemove);
    this.slideDeckFuture   = slideDeckFuture;
    this.slideDeckFuture.addListener(this.slideDeckListener);
  }

  public void setImageResource(@NonNull MasterSecret masterSecret, @NonNull Slide slide,
                               boolean showControls, boolean showRemove)
  {
    if (Util.equals(slide, this.slide)) {
      Log.w(TAG, "Not re-loading slide " + slide.asAttachment().getDataUri());
      return;
    }

    if (!isContextValid()) {
      Log.w(TAG, "Not loading slide, context is invalid");
      return;
    }

    if (showControls) {
      getTransferControls().setSlide(slide);
      getTransferControls().setDownloadClickListener(new DownloadClickDispatcher());
    } else if (transferControls.isPresent()) {
      getTransferControls().setVisibility(View.GONE);
    }

    Log.w(TAG, "loading part with id " + slide.asAttachment().getDataUri()
               + ", progress " + slide.getTransferState());

    this.slide = slide;

    if      (slide.getThumbnailUri() != null) buildThumbnailGlideRequest(slide, masterSecret, showRemove).into(image);
    else if (slide.hasPlaceholder())          buildPlaceholderGlideRequest(slide).into(image);
    else                                      Glide.clear(image);
  }

  public void setImageResource(@NonNull MasterSecret masterSecret, @NonNull Uri uri) {
    if (transferControls.isPresent()) getTransferControls().setVisibility(View.GONE);

    Glide.with(getContext()).load(new DecryptableUri(masterSecret, uri))
         .crossFade()
         .transform(new RoundedCorners(getContext(), true, radius, backgroundColorHint))
         .into(image);
  }

  public void setThumbnailClickListener(ThumbnailClickListener listener) {
    this.thumbnailClickListener = listener;
  }

  public void setRemoveClickListener(OnClickListener listener) {
    getRemoveButton().setOnClickListener(listener);
    final int pad = getResources().getDimensionPixelSize(R.dimen.media_bubble_remove_button_size);
    image.setPadding(pad, pad, pad, 0);
  }

  public void setDownloadClickListener(ThumbnailClickListener listener) {
    this.downloadClickListener = listener;
  }

  public void clear() {
    if (isContextValid())             Glide.clear(image);
    if (slideDeckFuture != null)      slideDeckFuture.removeListener(slideDeckListener);
    if (transferControls.isPresent()) getTransferControls().clear();
    slide             = null;
    slideDeckFuture   = null;
    slideDeckListener = null;
  }

  public void showProgressSpinner() {
    getTransferControls().showProgressSpinner();
  }

  @TargetApi(VERSION_CODES.JELLY_BEAN_MR1)
  private boolean isContextValid() {
    return !(getContext() instanceof Activity)            ||
           VERSION.SDK_INT < VERSION_CODES.JELLY_BEAN_MR1 ||
           !((Activity)getContext()).isDestroyed();
  }

  private GenericRequestBuilder buildThumbnailGlideRequest(@NonNull Slide slide, @NonNull MasterSecret masterSecret, boolean showRemove) {
    DrawableRequestBuilder<DecryptableUri> builder = Glide.with(getContext()).load(new DecryptableUri(masterSecret, slide.getThumbnailUri()))
                                                          .crossFade()
                                                          .transform(new RoundedCorners(getContext(), true, radius, backgroundColorHint));

    if (showRemove) {
      builder = builder.listener(new ThumbnailSetListener(slide.asAttachment()));
    }

    if (slide.isInProgress()) return builder;
    else                      return builder.error(R.drawable.ic_missing_thumbnail_picture);
  }

  private GenericRequestBuilder buildPlaceholderGlideRequest(Slide slide) {
    return Glide.with(getContext()).load(slide.getPlaceholderRes(getContext().getTheme()))
                                   .asBitmap()
                                   .fitCenter();
  }

  private class SlideDeckListener implements FutureTaskListener<SlideDeck> {
    private final MasterSecret masterSecret;
    private final boolean      showControls;
    private final boolean      showRemove;

    public SlideDeckListener(@NonNull MasterSecret masterSecret, boolean showControls, boolean showRemove) {
      this.masterSecret = masterSecret;
      this.showControls = showControls;
      this.showRemove   = showRemove;
    }

    @Override
    public void onSuccess(final SlideDeck slideDeck) {
      if (slideDeck == null) return;

      final Slide slide = slideDeck.getThumbnailSlide();

      if (slide != null) {
        Util.runOnMain(new Runnable() {
          @Override
          public void run() {
            setImageResource(masterSecret, slide, showControls, showRemove);
          }
        });
      } else {
        Util.runOnMain(new Runnable() {
          @Override
          public void run() {
            Log.w(TAG, "Resolved slide was null!");
            setVisibility(View.GONE);
          }
        });
      }
    }

    @Override
    public void onFailure(Throwable error) {
      Log.w(TAG, error);
      Util.runOnMain(new Runnable() {
        @Override
        public void run() {
          Log.w(TAG, "onFailure!");
          setVisibility(View.GONE);
        }
      });
    }
  }

  public interface ThumbnailClickListener {
    void onClick(View v, Slide slide);
  }

  private class ThumbnailClickDispatcher implements View.OnClickListener {
    @Override
    public void onClick(View view) {
      if (thumbnailClickListener            != null &&
          slide                             != null &&
          slide.asAttachment().getDataUri() != null &&
          slide.getTransferState()          == AttachmentDatabase.TRANSFER_PROGRESS_DONE)
      {
        thumbnailClickListener.onClick(view, slide);
      } else if (parentClickListener != null) {
        parentClickListener.onClick(view);
      }
    }
  }

  private class DownloadClickDispatcher implements View.OnClickListener {
    @Override
    public void onClick(View view) {
      if (downloadClickListener != null && slide != null) {
        downloadClickListener.onClick(view, slide);
      }
    }
  }

  private class ThumbnailSetListener implements RequestListener<Object, GlideDrawable> {

    private final Attachment attachment;

    public ThumbnailSetListener(@NonNull Attachment attachment) {
      this.attachment = attachment;
    }

    @Override
    public boolean onException(Exception e, Object model, Target<GlideDrawable> target, boolean isFirstResource) {
      return false;
    }

    @Override
    public boolean onResourceReady(GlideDrawable resource, Object model, Target<GlideDrawable> target, boolean isFromMemoryCache, boolean isFirstResource) {
      if (resource instanceof GlideBitmapDrawable) {
        Log.w(TAG, "onResourceReady() for a Bitmap. Saving.");
        attachment.setThumbnail(((GlideBitmapDrawable) resource).getBitmap());
      }
      LayoutParams layoutParams = (LayoutParams) getRemoveButton().getLayoutParams();
      if (resource.getIntrinsicWidth() < getWidth()) {
        layoutParams.topMargin   = 0;
        layoutParams.rightMargin = Math.max(0, (getWidth() - image.getPaddingRight() - resource.getIntrinsicWidth()) / 2);
      } else {
        layoutParams.topMargin   = Math.max(0, (getHeight() - image.getPaddingTop() - resource.getIntrinsicHeight()) / 2);
        layoutParams.rightMargin = 0;
      }
      getRemoveButton().setLayoutParams(layoutParams);
      return false;
    }
  }
}
