import { PGlite } from '@electric-sql/pglite';
import { readFile } from 'node:fs/promises';
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID as uuid } from 'node:crypto';
const db=new PGlite();
const org=uuid(),branch=uuid(),device=uuid(),install=uuid(),product=uuid(),actor=uuid();
const read=p=>readFile(new URL(p,import.meta.url),'utf8');
before(async()=>{
 await db.exec(`create role anon;create role authenticated;create role service_role;
 create schema auth;create table auth.users(id uuid primary key);
 create function auth.uid() returns uuid language sql as $$ select null::uuid $$;`);
 await db.exec((await read('../drafts/brewpos_v2_dev_schema.sql')).replace('create extension if not exists pgcrypto;',''));
 await db.exec(await read('../live/brewpos_ingest_sale.sql'));
 await db.exec(await read('../live/brewpos_inventory.sql'));
 await db.exec(`insert into auth.users values('${actor}');
 insert into brew_organizations(id,name,slug) values('${org}','Inventory Test','inventory-test');
 insert into brew_branches(id,organization_id,name) values('${branch}','${org}','Test branch');
 insert into brew_memberships(organization_id,user_id,role) values('${org}','${actor}','owner');
 insert into brew_devices(id,organization_id,branch_id,installation_id) values('${device}','${org}','${branch}','${install}');
 insert into brew_products(id,organization_id,branch_id,name,price_centavos,stock_quantity) values('${product}','${org}','${branch}','Latte',10000,20);`);
});
after(()=>db.close());
const sale=(qty=2)=>{
 const id=uuid();return {payload_version:2,event_type:'sale.completed',event_id:id,client_sale_id:id,installation_id:install,
 local_receipt_no:'TEST-'+id,service_type:'Takeout',payment_method:'Cash',subtotal_centavos:qty*10000,discount_centavos:0,
 total_centavos:qty*10000,tendered_centavos:qty*10000,change_centavos:0,created_offline_at_ms:Date.now(),
 line_items:[{product_id:product,product_name:'Latte',quantity:qty,unit_centavos:10000,line_centavos:qty*10000}]};
};
async function ingest(event){return (await db.query('select brew_ingest_sale($1,$2,$3,$4) ack',[org,branch,device,event])).rows[0].ack;}
async function stock(){return Number((await db.query('select stock_quantity from brew_products where id=$1',[product])).rows[0].stock_quantity);}
async function count(table){return Number((await db.query(`select count(*) n from ${table}`)).rows[0].n);}
async function rollback(fn){await db.exec('begin');try{await fn();}finally{await db.exec('rollback');}}
test('sale and duplicate produce one ledger movement and one payment',()=>rollback(async()=>{
 const e=sale();const before=await stock();assert.equal((await ingest(e)).inventory_applied,true);
 assert.equal((await ingest(e)).duplicate,true);assert.equal(await stock(),before-2);
 assert.equal(await count('brew_inventory_movements'),1);assert.equal(await count('brew_payments'),1);
}));
test('conflicting duplicate payload rejected, original sale retained',()=>rollback(async()=>{
 const e=sale();await ingest(e);await db.exec('savepoint conflict');
 await assert.rejects(()=>ingest({...e,notes:'changed'}),/Conflicting event/);
 await db.exec('rollback to conflict');assert.equal(await count('brew_sales'),1);
}));
test('unknown product rolls back event, financial records and all stock changes',()=>rollback(async()=>{
 const e=sale();e.line_items[0].product_id=uuid();await db.exec('savepoint bad');
 await assert.rejects(()=>ingest(e),/Product outside/);await db.exec('rollback to bad');
 assert.equal(await count('brew_sales'),0);assert.equal(await count('brew_sync_events'),0);assert.equal(await stock(),20);
}));
test('multi-line same product deducts aggregated units once',()=>rollback(async()=>{
 const e=sale(2);e.line_items=[{...e.line_items[0],quantity:1,line_centavos:10000},{...e.line_items[0],quantity:1,line_centavos:10000}];
 await ingest(e);assert.equal(await stock(),18);assert.equal(await count('brew_inventory_movements'),1);
}));
test('two offline sales retain paid revenue and expose shortage',()=>rollback(async()=>{
 await ingest(sale(15));await ingest(sale(15));assert.equal(await stock(),-10);assert.equal(await count('brew_sales'),2);
}));
test('stock delta retry applies once; cashier cannot adjust',()=>rollback(async()=>{
 const e={payload_version:2,event_type:'stock.adjusted',event_id:uuid(),installation_id:install,product_id:product,delta:5,reason:'Received supplies'};
 const adjust=()=>db.query('select brew_ingest_stock($1,$2,$3,$4,$5) ack',[org,branch,device,actor,e]);
 await adjust();assert.equal((await adjust()).rows[0].ack.duplicate,true);assert.equal(await stock(),25);
 await db.exec("update brew_memberships set role='cashier';savepoint forbidden");
 await assert.rejects(adjust,/Manager authorization/);await db.exec('rollback to forbidden');
}));
test('catalog complete and isolated; invoker RPC not executable by public clients',async()=>{
 assert.equal((await db.query('select brew_catalog($1,$2) catalog',[uuid(),branch])).rows[0].catalog.length,0);
 const r=await db.query(`select has_function_privilege('anon','brew_ingest_stock(uuid,uuid,uuid,uuid,jsonb)','EXECUTE') allowed,
 has_function_privilege('authenticated','brew_ingest_sale(uuid,uuid,uuid,jsonb)','EXECUTE') sale`);
 assert.equal(r.rows[0].allowed,false);assert.equal(r.rows[0].sale,false);
});
test('legacy v1 ACK explicitly reports inventory was not applied',()=>rollback(async()=>{
 const e=sale();e.payload_version=1;delete e.line_items[0].product_id;e.line_items[0].local_product_id=1;
 assert.equal((await ingest(e)).inventory_applied,false);assert.equal(await stock(),20);
}));
test('missing event type and missing items are rejected',()=>rollback(async()=>{
 for(const field of ['event_type','line_items','service_type']) {
  const e=sale();delete e[field];await db.exec('savepoint bad');await assert.rejects(()=>ingest(e));await db.exec('rollback to bad');
 }
 assert.equal(await count('brew_sales'),0);
}));
