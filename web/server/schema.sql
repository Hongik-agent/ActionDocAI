-- Install atomically; migrations and reservations share this lock.
begin;
select pg_advisory_xact_lock(162730492);
create table if not exists public.actiondoc_jobs (
  job uuid primary key default gen_random_uuid(), owner uuid not null, fingerprint text not null,
  started timestamptz not null default now(), result jsonb
);
alter table public.actiondoc_jobs enable row level security;
revoke all on public.actiondoc_jobs from public, anon, authenticated;
grant select,update on public.actiondoc_jobs to service_role;
create index if not exists actiondoc_usage on public.actiondoc_jobs(started);
create index if not exists actiondoc_cache on public.actiondoc_jobs(owner,fingerprint,started);
-- Contains no user/document identifiers; account deletion cannot reset this counter.
create table if not exists public.actiondoc_daily_usage (
 day date primary key, attempts bigint not null check(attempts>=0)
);
alter table public.actiondoc_daily_usage enable row level security;
revoke all on public.actiondoc_daily_usage from public, anon, authenticated;
grant select on public.actiondoc_daily_usage to service_role;
-- Reapplication must neither double-count nor lower counts after users leave.
insert into public.actiondoc_daily_usage(day,attempts)
 select (started at time zone 'Asia/Seoul')::date,count(*) from public.actiondoc_jobs group by 1
 on conflict(day) do update set attempts=greatest(actiondoc_daily_usage.attempts,excluded.attempts);
create or replace function public.actiondoc_reserve(u uuid, f text, personal integer, total integer)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare prior actiondoc_jobs; new_id uuid; boundary timestamptz; usage_day date;
begin
 perform pg_advisory_xact_lock(162730492);
 usage_day := (now() at time zone 'Asia/Seoul')::date;
 boundary := date_trunc('day',now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul';
 delete from actiondoc_jobs where started < now()-interval '7 days';
 select * into prior from actiondoc_jobs where owner=u and fingerprint=f order by started desc limit 1;
 if prior.result is not null then return jsonb_build_object('status','cached','result',prior.result); end if;
 if prior.started > now()-interval '3 minutes' then return jsonb_build_object('status','busy'); end if;
 if coalesce((select attempts from actiondoc_daily_usage where day=usage_day),0)>=total then return jsonb_build_object('status','global'); end if;
 if (select count(*) from actiondoc_jobs where started>=boundary and owner=u)>=personal then return jsonb_build_object('status','personal'); end if;
 insert into actiondoc_jobs(owner,fingerprint) values(u,f) returning job into new_id;
 insert into actiondoc_daily_usage(day,attempts) values(usage_day,1)
  on conflict(day) do update set attempts=actiondoc_daily_usage.attempts+1;
 return jsonb_build_object('status','reserved','job',new_id);
end $$;
revoke all on function public.actiondoc_reserve(uuid,text,integer,integer) from public, anon, authenticated;
grant execute on function public.actiondoc_reserve(uuid,text,integer,integer) to service_role;

-- NOT VALID also protects new rows and cascades deletes for existing users.
-- Legacy rows whose accounts no longer exist expire with the normal cleanup.
do $$ begin
 if not exists (select from pg_constraint where conrelid='public.actiondoc_jobs'::regclass and conname='actiondoc_owner_fk') then
  alter table public.actiondoc_jobs add constraint actiondoc_owner_fk foreign key(owner) references auth.users(id) on delete cascade not valid;
 end if;
end $$;
create or replace function public.actiondoc_delete_my_account() returns void
language plpgsql security definer set search_path='' as $$
begin
 delete from auth.users where id=(select auth.uid());
 if not found then raise exception 'Account unavailable'; end if;
end $$;
revoke all on function public.actiondoc_delete_my_account() from public, anon, service_role;
grant execute on function public.actiondoc_delete_my_account() to authenticated;

commit;
