-- Complimentary lifetime access (e.g. for Google Play app review or customer support), granted by an
-- administrator without a payment. Such entitlements carry a reason instead of a source order, so
-- accounting only ever contains real payments.
ALTER TABLE entitlements ALTER COLUMN source_order_id DROP NOT NULL;
ALTER TABLE entitlements ADD COLUMN grant_reason text;
ALTER TABLE entitlements ADD CONSTRAINT entitlements_source_or_reason
    CHECK (source_order_id IS NOT NULL OR grant_reason IS NOT NULL);
