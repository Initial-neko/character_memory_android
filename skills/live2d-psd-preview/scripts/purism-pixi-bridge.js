// Local preview compatibility adapter: Pixi's Cubism 4 wrapper reads the
// v5 render-order array from drawables; Purism exposes it on the Model.
window.Live2DCubismCore = window.PurismCore;
const purismFromMoc = PurismCore.Model.fromMoc;
PurismCore.Model.fromMoc = function (moc) {
  const model = purismFromMoc.call(this, moc);
  if (model) {
    model.drawables.renderOrders = model.renderOrders;
    // Pixi 0.4's Cubism renderer clears the mask framebuffer every frame,
    // then skips mask sources without VertexPositionsDidChange (bit 32).
    // Purism accurately leaves that bit unset for unchanged geometry.
    // Such sources still need drawing into the freshly cleared framebuffer.
    const maskSources = new Set();
    for (const masks of model.drawables.masks) {
      for (const index of masks) maskSources.add(index);
    }
    // Pixi calls resetDynamicFlags immediately after core.update(), so the
    // compatibility dirty bits must be applied after that reset.
    const nativeReset = model.drawables.resetDynamicFlags;
    model.drawables.resetDynamicFlags = function (...args) {
      const result = nativeReset.apply(this, args);
      for (const index of maskSources) this.dynamicFlags[index] |= 32;
      return result;
    };
  }
  return model;
};
