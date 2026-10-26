import { expect, test } from '@playwright/test';

test.describe('账号验证与密码找回', () => {
  test('找回密码使用统一成功提示，不暴露邮箱是否存在', async ({ page }) => {
    let requestBody = '';
    await page.route('**/api/auth/password-reset/request', async route => {
      requestBody = route.request().postData() ?? '';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: null, success: true }),
      });
    });

    await page.goto('/login');
    await page.getByRole('button', { name: '忘记密码？' }).click();
    await expect(page.getByRole('heading', { name: '找回密码' })).toBeVisible();
    await page.getByLabel('邮箱').fill('unknown@example.com');
    await page.getByRole('button', { name: '发送重置链接' }).click();

    await expect(page.getByRole('status')).toContainText('如果该邮箱已注册');
    expect(requestBody).toContain('unknown@example.com');
  });

  test('验证链接失败时展示可操作错误并能返回登录', async ({ page }) => {
    await page.route('**/api/auth/verify-email', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 12006, message: '链接无效或已过期', data: null, success: false }),
      });
    });

    await page.goto('/verify-email?token=invalid-token');
    await expect(page.getByRole('heading', { name: '邮箱验证' })).toBeVisible();
    await expect(page.getByText('链接无效或已过期')).toBeVisible();
    await page.getByRole('button', { name: '返回登录' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});
