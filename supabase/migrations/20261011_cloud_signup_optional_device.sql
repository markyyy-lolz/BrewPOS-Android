-- Additive production migration: Cloud POS registration is email/merchant-based.
-- Device IDs are optional for cloud signup; required later for device-bound offline BP1.
ALTER TABLE public.brew_signup_requests ALTER COLUMN android_device_id DROP NOT NULL;
ALTER TABLE public.brew_signup_requests DROP CONSTRAINT IF EXISTS brew_signup_requests_android_device_id_check;
ALTER TABLE public.brew_signup_requests ADD CONSTRAINT brew_signup_requests_android_device_id_check
  CHECK (android_device_id IS NULL OR android_device_id ~ '^[0-9a-f]{16}$');
