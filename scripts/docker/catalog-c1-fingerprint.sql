-- Fingerprint only non-secret C1 attributes and its own ACL entries, not passwords.
SELECT md5(json_build_object(
 'role',(SELECT json_build_array(oid,rolcanlogin,rolinherit,rolsuper,rolcreatedb,rolcreaterole,
    rolreplication,rolbypassrls,rolconnlimit,rolconfig,rolvaliduntil) FROM pg_roles WHERE rolname='ls_core_catalog_reader_dev'),
 'grants',(SELECT coalesce(json_agg(row_to_json(x) ORDER BY kind,oid,subid,grantor,privilege_type),'[]'::json)
    FROM (
      SELECT 'database' AS kind,d.oid,0 AS subid,a.grantor,a.privilege_type,a.is_grantable FROM pg_database d,
        LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a WHERE a.grantee='ls_core_catalog_reader_dev'::regrole
      UNION ALL SELECT 'schema',n.oid,0,a.grantor,a.privilege_type,a.is_grantable FROM pg_namespace n,
        LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a WHERE a.grantee='ls_core_catalog_reader_dev'::regrole
      UNION ALL SELECT 'table',c.oid,0,a.grantor,a.privilege_type,a.is_grantable FROM pg_class c,
        LATERAL aclexplode(c.relacl) a WHERE a.grantee='ls_core_catalog_reader_dev'::regrole
      UNION ALL SELECT 'column',c.attrelid,c.attnum,a.grantor,a.privilege_type,a.is_grantable FROM pg_attribute c,
        LATERAL aclexplode(c.attacl) a WHERE a.grantee='ls_core_catalog_reader_dev'::regrole
    ) x)
)::text);
