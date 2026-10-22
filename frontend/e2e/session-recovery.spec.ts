import { test,expect } from '@playwright/test';
import fs from 'node:fs';
const account=JSON.parse(fs.readFileSync('../.local/verification-account.json','utf8'));
test('unsaved draft survives logout and session-expiration redirect',async({page})=>{
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.stack || e.message));
 const login=async()=>{await page.goto('/login');await page.getByLabel('Email',{exact:true}).fill(account.email);await page.getByLabel('Password',{exact:true}).fill(account.password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.getByRole('heading',{name:'DevFest Programming Contest',exact:true})).toBeVisible();await page.goto('/problems/1');await expect(page.locator('.monaco-editor')).toBeVisible();};
 const read=()=>page.evaluate(()=> (window as unknown as {monaco?:{editor:{getModels():{getValue():string}[]}}}).monaco?.editor.getModels()[0]?.getValue());
 await login();const draft='# logout recovery '+Date.now();await page.locator('.monaco-editor').click();await page.keyboard.press('Control+a');await page.keyboard.insertText(draft);await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.getByRole('button',{name:'Sign in',exact:true})).toBeVisible();await login();await expect.poll(read).toBe(draft);
 // Invalid access token exercises one refresh; an invalid refresh then redirects safely.
 await page.evaluate(()=>sessionStorage.setItem('devfest.access','expired-test-token'));await page.reload();await expect(page.locator('.monaco-editor')).toBeVisible();await expect.poll(read).toBe(draft);
 await page.evaluate(()=>{sessionStorage.setItem('devfest.access','expired-test-token');sessionStorage.setItem('devfest.refresh','invalid-test-refresh');});await page.goto('/submissions');await expect(page.getByRole('button',{name:'Sign in',exact:true})).toBeVisible();await login();await expect.poll(read).toBe(draft);// Monaco 0.55.1 can reject its own canceled disposal task during rapid route teardown.
 // Keep that known library warning visible in evidence; fail all other browser errors.
 fs.writeFileSync('../docs/browser-teardown-warnings.json',JSON.stringify(errors,null,2));
 expect(errors.filter(e=>!(e.startsWith('Canceled: Canceled')&&e.includes('/monaco/vs/')))).toEqual([]);
});
