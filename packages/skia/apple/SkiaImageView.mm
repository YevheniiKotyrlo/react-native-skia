#import "SkiaImageView.h"

#import <QuartzCore/CATransaction.h>

#import <React/RCTFabricComponentsPlugins.h>

#import <react/renderer/components/rnskia/ComponentDescriptors.h>
#import <react/renderer/components/rnskia/EventEmitters.h>
#import <react/renderer/components/rnskia/Props.h>
#import <react/renderer/components/rnskia/RCTComponentViewHelpers.h>

#import "SkiaPresentedImages.h"

using namespace facebook::react;

@interface SkiaImageView () <SkiaPresentedImageObserver>
@end

@implementation SkiaImageView {
  // A layer of its own: RCTViewComponentView draws backgrounds and borders
  // into the view's layer.
  CALayer *_imageLayer;
  // 0 shows nothing; see RNSkPresentedImages::idOf for the others.
  uint32_t _imageId;
}

- (instancetype)initWithFrame:(CGRect)frame {
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps =
        std::make_shared<const SkiaImageViewProps>();
    _props = defaultProps;
    _imageId = 0;
    _imageLayer = [CALayer layer];
    // The image fills the view, and appears without the implicit fade a
    // layer's new contents would get.
    _imageLayer.contentsGravity = kCAGravityResize;
    _imageLayer.actions = @{
      @"contents" : [NSNull null],
      @"bounds" : [NSNull null],
      @"position" : [NSNull null],
    };
    [self.layer addSublayer:_imageLayer];
  }
  return self;
}

- (void)prepareForRecycle {
  [super prepareForRecycle];
  [self showImageId:0];
}

- (void)layoutSubviews {
  [super layoutSubviews];
  _imageLayer.frame = self.layer.bounds;
}

- (void)showImageId:(uint32_t)imageId {
  if (imageId == _imageId) {
    return;
  }
  if (_imageId != 0) {
    [SkiaPresentedImages unobserveImageId:_imageId observer:self];
  }
  _imageId = imageId;
  if (imageId == 0) {
    _imageLayer.contents = nil;
    return;
  }
  // Until the new image is published, the layer keeps the one it shows: a
  // change of image never shows nothing in between.
  CGImageRef image = [SkiaPresentedImages observeImageId:imageId observer:self];
  if (image != nil) {
    _imageLayer.contents = (__bridge id)image;
  }
}

- (void)presentedImageDidPublish:(CGImageRef)image {
  _imageLayer.contents = (__bridge id)image;
}

#pragma mark - Props

+ (ComponentDescriptorProvider)componentDescriptorProvider {
  return concreteComponentDescriptorProvider<
      SkiaImageViewComponentDescriptor>();
}

- (void)updateProps:(const Props::Shared &)props
           oldProps:(const Props::Shared &)oldProps {
  const auto &newProps =
      *std::static_pointer_cast<const SkiaImageViewProps>(props);
  [super updateProps:props oldProps:oldProps];
  [self showImageId:static_cast<uint32_t>(newProps.presentedImageId)];
}

@end

Class<RCTComponentViewProtocol> SkiaImageViewCls(void) {
  return SkiaImageView.class;
}
