ALTER TABLE user_accounts
    ADD COLUMN phone_verified_at TIMESTAMP WITH TIME ZONE;

CREATE FUNCTION invalidate_account_phone_verification() RETURNS trigger
LANGUAGE plpgsql AS $function$
BEGIN
    IF NEW.user_phone_number IS DISTINCT FROM OLD.user_phone_number THEN
        NEW.phone_verified_at := NULL;
        NEW.session_version := GREATEST(NEW.session_version, OLD.session_version + 1);
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER invalidate_account_phone_verification
    BEFORE UPDATE OF user_phone_number ON user_accounts
    FOR EACH ROW EXECUTE FUNCTION invalidate_account_phone_verification();
