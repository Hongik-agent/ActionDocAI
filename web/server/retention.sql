-- Run after schema.sql, as the Supabase database administrator. Safe to rerun.
create extension if not exists pg_cron;
select cron.schedule('actiondoc-expire-jobs', '0 * * * *',
  $$delete from public.actiondoc_jobs where started <= now() - interval '7 days'; delete from public.actiondoc_daily_usage where day < (now() at time zone 'Asia/Seoul')::date - 7$$);
