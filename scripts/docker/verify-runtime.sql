\set ON_ERROR_STOP on
DO $$ BEGIN
  IF current_user<>'ls_core_app_dev' OR session_user<>current_user OR current_database()<>'ls_core_dev'
     OR NOT has_database_privilege(current_user,current_database(),'CONNECT')
     OR has_database_privilege(current_user,current_database(),'CREATE,TEMPORARY')
     OR EXISTS (SELECT FROM pg_database WHERE datallowconn AND datname<>current_database()
          AND has_database_privilege(current_user,oid,'CONNECT'))
     OR EXISTS (SELECT FROM pg_namespace WHERE has_schema_privilege(current_user,oid,'CREATE'))
     OR EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE c.relkind='r' AND n.nspname IN ('identity','platform','tenant','security','catalog','integration','audit','operations')
          AND (has_schema_privilege(current_user,n.oid,'USAGE')
               OR has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
               OR has_any_column_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
    RAISE EXCEPTION 'Runtime privileges exceed Docker contract';
  END IF;
END $$;
SELECT 1;
