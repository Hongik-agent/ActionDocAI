-- Install in the AI service's Supabase SQL editor.
create table if not exists public.actiondoc_jobs (
  job uuid primary key default gen_random_uuid(), owner uuid not null, fingerprint text not null,
  started timestamptz not null default now(), result jsonb
);
alter table public.actiondoc_jobs enable row level security;
revoke all on public.actiondoc_jobs from public, anon, authenticated;
grant select,update on public.actiondoc_jobs to service_role;
create index if not exists actiondoc_usage on public.actiondoc_jobs(started);
create index if not exists actiondoc_cache on public.actiondoc_jobs(owner,fingerprint,started);
create or replace function public.actiondoc_reserve(u uuid, f text, personal integer, total integer)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare prior actiondoc_jobs; new_id uuid; boundary timestamptz;
begin
 perform pg_advisory_xact_lock(162730492);
 boundary := date_trunc('day',now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul';
 delete from actiondoc_jobs where started < now()-interval '7 days';
 select * into prior from actiondoc_jobs where owner=u and fingerprint=f order by started desc limit 1;
 if prior.result is not null then return jsonb_build_object('status','cached','result',prior.result); end if;
 if prior.started > now()-interval '3 minutes' then return jsonb_build_object('status','busy'); end if;
 if (select count(*) from actiondoc_jobs where started>=boundary)>=total then return jsonb_build_object('status','global'); end if;
 if (select count(*) from actiondoc_jobs where started>=boundary and owner=u)>=personal then return jsonb_build_object('status','personal'); end if;
 insert into actiondoc_jobs(owner,fingerprint) values(u,f) returning job into new_id;
 return jsonb_build_object('status','reserved','job',new_id);
end $$;
revoke all on function public.actiondoc_reserve(uuid,text,integer,integer) from public, anon, authenticated;
grant execute on function public.actiondoc_reserve(uuid,text,integer,integer) to service_role;
