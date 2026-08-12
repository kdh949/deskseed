\set ON_ERROR_STOP on

select name, setting, unit, source
from pg_settings
where name in (
    'shared_buffers',
    'work_mem',
    'maintenance_work_mem',
    'effective_cache_size',
    'random_page_cost',
    'seq_page_cost',
    'max_parallel_workers_per_gather',
    'jit'
)
order by name;
