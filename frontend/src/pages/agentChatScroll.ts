/**
 * 判断用户是否仍处于对话底部附近。
 * 底部附近才自动跟随，用户主动上滑阅读历史时不抢回滚动位置。
 */
export function isNearChatBottom(
  scrollTop: number,
  clientHeight: number,
  scrollHeight: number,
  threshold = 96,
) {
  return scrollHeight - (scrollTop + clientHeight) <= threshold;
}
