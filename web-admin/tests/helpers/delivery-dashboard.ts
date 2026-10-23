import {expect, type Locator} from '@playwright/test';

export async function dashboardRow(block:Locator,name:string) {
  // Follow the actual configured pagination; unrelated retained deliveries may precede this batch.
  for(let visited=0;visited<100;visited++) {
    await expect(block.getByRole('cell').first()).toBeVisible();
    const row=block.getByRole('row').filter({hasText:name});
    if(await row.count()) {await expect(row).toHaveCount(1);return row;}
    const next=block.getByRole('button',{name:'下一页',exact:true});
    await expect(next).toBeEnabled();
    const before=await block.innerText();await next.click();
    await expect.poll(()=>block.innerText()).not.toBe(before);
  }
  throw Error(`Delivery was not found within 100 dashboard pages: ${name}`);
}
