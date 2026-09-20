import { getEnabled, listListed, listVendors } from "/manage/api.js?v=20260920p";

export async function loadModelOverview() {
  const vendors = await listVendors();
  if (!vendors.ok) {
    return vendors;
  }
  const listed = await listListed();
  if (!listed.ok) {
    return listed;
  }
  const enabled = await getEnabled();
  if (!enabled.ok) {
    return enabled;
  }
  const current = enabled.body && enabled.body.enabled;
  return {
    ok: true,
    vendors: vendors.body || [],
    listed: (listed.body && listed.body.entries) || [],
    enabled: current ? { vendorId: current.vendorId, modelId: current.modelId } : null,
  };
}
