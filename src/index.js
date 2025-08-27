/**
 * Cloudflare Worker - 定时访问网址并发送结果到微信群机器人
 * 每天早上6点执行，访问所有ACCESS_开头的环境变量中的网址
 */

// 企业微信机器人 Webhook URL（默认配置）
const DEFAULT_WEBHOOK_URL = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send";
// TEST ROWS 1
// 支持的返回格式类型
const RETURN_TYPES = {
  TEXT: 'text',
  HTML: 'html'
};

// 默认超时时间配置（毫秒）
const DEFAULT_TIMEOUT = 30000; // 30秒 - 请求超时时间
const DEFAULT_RESPONSE_TIMEOUT = 60000; // 60秒 - 响应体加载超时时间
const DEFAULT_CONCURRENCY_LIMIT = 5; // 并发限制
const DEFAULT_BATCH_DELAY = 1000; // 批次间延迟（毫秒）

/**
 * 获取所有ACCESS_开头的环境变量
 * @param {Object} env - 环境变量对象
 * @returns {Object} 包含所有ACCESS_开头的环境变量
 */
function getAccessUrls(env) {
  const accessUrls = {};
  
  // 遍历所有环境变量，找出ACCESS_开头的
  for (const [key, value] of Object.entries(env)) {
    if (key.startsWith('ACCESS_') && value) {
      accessUrls[key] = value;
    }
  }
  
  return accessUrls;
}

/**
 * 获取返回格式类型
 * @param {Object} env - 环境变量对象
 * @returns {string} 返回格式类型
 */
function getReturnType(env) {
  return env.RETURN_TYPE || RETURN_TYPES.TEXT;
}

/**
 * 获取通知配置
 * @param {Object} env - 环境变量对象
 * @returns {Object} 通知配置
 */
function getNotificationConfig(env) {
  return {
    webhookUrl: env.WEBHOOK_URL || DEFAULT_WEBHOOK_URL,
    type: env.NOTIFICATION_TYPE || 'wechat', // 支持 wechat, webhook, email 等
    enabled: env.NOTIFICATION_ENABLED !== 'false', // 默认启用通知
    // 邮件配置
    mailto: env.MAILTO || '',
    mailSubject: env.MAIL_SUBJECT || '定时任务执行报告'
  };
}

/**
 * 获取超时配置
 * @param {Object} env - 环境变量对象
 * @returns {Object} 超时配置
 */
function getTimeoutConfig(env) {
  return {
    timeout: parseInt(env.REQUEST_TIMEOUT) || DEFAULT_TIMEOUT,
    responseTimeout: parseInt(env.RESPONSE_TIMEOUT) || DEFAULT_RESPONSE_TIMEOUT,
    concurrencyLimit: parseInt(env.CONCURRENCY_LIMIT) || DEFAULT_CONCURRENCY_LIMIT,
    batchDelay: parseInt(env.BATCH_DELAY) || DEFAULT_BATCH_DELAY
  };
}

/**
 * 访问单个URL并返回结果
 * @param {string} url - 要访问的URL
 * @param {string} name - 环境变量名称
 * @param {Object} timeoutConfig - 超时配置
 * @returns {Promise<Object>} 访问结果
 */
async function accessUrl(url, name, timeoutConfig) {
  const startTime = Date.now();
  const { timeout, responseTimeout } = timeoutConfig;
  
  try {
    // 创建 AbortController 用于请求超时控制
    const requestController = new AbortController();
    const requestTimeoutId = setTimeout(() => requestController.abort(), timeout);
    
    const response = await fetch(url, {
      method: 'GET',
      signal: requestController.signal,
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        'Accept-Encoding': 'gzip, deflate, br',
        'DNT': '1',
        'Connection': 'keep-alive',
        'Upgrade-Insecure-Requests': '1',
        'Sec-Fetch-Dest': 'document',
        'Sec-Fetch-Mode': 'navigate',
        'Sec-Fetch-Site': 'none',
        'Sec-Fetch-User': '?1',
        'Cache-Control': 'max-age=0'
      },
      cf: {
        cacheTtl: 0, // 不缓存
        cacheEverything: false
      }
    });
    
    clearTimeout(requestTimeoutId);
    
    // 创建响应体加载超时控制
    const responseController = new AbortController();
    const responseTimeoutId = setTimeout(() => responseController.abort(), responseTimeout);
    
    // 等待响应体完全加载，确保获取完整内容
    let responseBody = '';
    let isComplete = false;
    
    try {
      // 读取响应体，确保完整加载，并设置超时控制
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        
        responseBody += decoder.decode(value, { stream: true });
        
        // 检查是否超时
        if (Date.now() - startTime > responseTimeout) {
          responseController.abort();
          throw new Error(`响应体加载超时 (${responseTimeout}ms)`);
        }
      }
      
      isComplete = true;
    } catch (readError) {
      // 如果读取响应体失败，标记为不完整
      isComplete = false;
      if (readError.name === 'AbortError') {
        console.warn(`响应体加载超时: ${readError.message}`);
      } else {
        console.warn(`读取响应体失败: ${readError.message}`);
      }
    } finally {
      clearTimeout(responseTimeoutId);
    }
    
    const endTime = Date.now();
    const responseTime = endTime - startTime;
    
    // 判断响应是否真正成功
    const isTrulySuccessful = response.ok && isComplete && response.status !== 206;
    
    // 对于206状态码，需要特殊处理
    let finalStatus = response.status;
    let finalStatusText = response.statusText;
    let finalSuccess = isTrulySuccessful;
    
    if (response.status === 206) {
      if (isComplete) {
        finalStatusText = 'Partial Content (已完整加载)';
        finalSuccess = true; // 如果完整加载了，认为是成功的
      } else {
        finalStatusText = 'Partial Content (未完整加载)';
        finalSuccess = false; // 如果未完整加载，认为是失败的
      }
    }
    
    return {
      name: name,
      url: url,
      status: finalStatus,
      statusText: finalStatusText,
      responseTime: responseTime,
      success: finalSuccess,
      isComplete: isComplete,
      responseSize: responseBody.length,
      timestamp: new Date().toISOString()
    };
  } catch (error) {
    clearTimeout(requestTimeoutId);
    clearTimeout(responseTimeoutId);
    const endTime = Date.now();
    const responseTime = endTime - startTime;
    
    let errorMessage = error.message;
    if (error.name === 'AbortError') {
      if (error.message.includes('响应体加载超时')) {
        errorMessage = `响应体加载超时 (${responseTimeout}ms)`;
      } else {
        errorMessage = `请求超时 (${timeout}ms)`;
      }
    }
    
    return {
      name: name,
      url: url,
      status: 'ERROR',
      statusText: errorMessage,
      responseTime: responseTime,
      success: false,
      isComplete: false,
      responseSize: 0,
      timestamp: new Date().toISOString()
    };
  }
}

/**
 * 批量访问所有配置的URL
 * @param {Object} accessUrls - 包含所有ACCESS_开头的环境变量
 * @param {Object} timeoutConfig - 超时配置
 * @returns {Promise<Array>} 所有访问结果
 */
async function batchAccessUrls(accessUrls, timeoutConfig) {
  const results = [];
  const { concurrencyLimit, batchDelay } = timeoutConfig;
  const urlEntries = Object.entries(accessUrls);
  
  for (let i = 0; i < urlEntries.length; i += concurrencyLimit) {
    const batch = urlEntries.slice(i, i + concurrencyLimit);
    const batchPromises = batch.map(([name, url]) => accessUrl(url, name, timeoutConfig));
    
    const batchResults = await Promise.all(batchPromises);
    results.push(...batchResults);
    
    // 批次间稍作延迟，避免过于频繁的请求
    if (i + concurrencyLimit < urlEntries.length) {
      await new Promise(resolve => setTimeout(resolve, batchDelay));
    }
  }
  
  return results;
}

/**
 * 格式化访问结果为文本
 * @param {Array} results - 访问结果数组
 * @param {string} returnType - 返回格式类型
 * @returns {string} 格式化后的结果
 */
function formatResults(results, returnType) {
  if (results.length === 0) {
    return returnType === RETURN_TYPES.HTML 
      ? "<p>⚠️ 没有找到任何ACCESS_开头的环境变量配置</p>"
      : "⚠️ 没有找到任何ACCESS_开头的环境变量配置";
  }
  
  if (returnType === RETURN_TYPES.HTML) {
    return formatResultsAsHTML(results);
  } else {
    return formatResultsAsText(results);
  }
}

/**
 * 格式化访问结果为HTML格式
 * @param {Array} results - 访问结果数组
 * @returns {string} 格式化后的HTML
 */
function formatResultsAsHTML(results) {
  const total = results.length;
  const success = results.filter(r => r.success).length;
  const failed = total - success;
  
  let html = `
    <!DOCTYPE html>
    <html lang="zh-CN">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <title>定时任务执行报告</title>
      <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 20px; background: #f5f5f5; }
        .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        .header { text-align: center; margin-bottom: 20px; padding: 20px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; border-radius: 8px; }
        .stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 15px; margin-bottom: 20px; }
        .stat-card { background: #f8f9fa; padding: 15px; border-radius: 6px; text-align: center; border-left: 4px solid #007bff; }
        .stat-number { font-size: 24px; font-weight: bold; color: #007bff; }
        .result-item { background: #f8f9fa; margin: 10px 0; padding: 15px; border-radius: 6px; border-left: 4px solid #28a745; }
        .result-item.error { border-left-color: #dc3545; }
        .result-name { font-weight: bold; margin-bottom: 5px; }
        .result-url { color: #6c757d; font-size: 14px; margin-bottom: 5px; }
        .result-status { margin-bottom: 5px; }
        .status-success { color: #28a745; }
        .status-error { color: #dc3545; }
        .result-time { color: #6c757d; font-size: 12px; }
        .timestamp { text-align: center; color: #6c757d; font-size: 14px; margin-top: 20px; }
      </style>
    </head>
    <body>
      <div class="container">
        <div class="header">
          <h1>🌅 定时任务执行报告</h1>
          <p>${new Date().toLocaleString('zh-CN')}</p>
        </div>
        
        <div class="stats">
          <div class="stat-card">
            <div class="stat-number">${total}</div>
            <div>总计</div>
          </div>
          <div class="stat-card">
            <div class="stat-number" style="color: #28a745;">${success}</div>
            <div>成功</div>
          </div>
          <div class="stat-card">
            <div class="stat-number" style="color: #dc3545;">${failed}</div>
            <div>失败</div>
          </div>
        </div>
        
        <h2>详细结果</h2>
  `;
  
  results.forEach((result, index) => {
    const statusClass = result.success ? 'status-success' : 'status-error';
    const itemClass = result.success ? 'result-item' : 'result-item error';
    const statusIcon = result.success ? '✅' : '❌';
    const statusText = result.success ? '成功' : '失败';
    const completionStatus = result.isComplete ? '完整' : '不完整';
    
    html += `
      <div class="${itemClass}">
        <div class="result-name">${index + 1}. ${statusIcon} ${result.name}</div>
        <div class="result-url">URL: ${result.url}</div>
        <div class="result-status ${statusClass}">状态: ${result.status} ${result.statusText}</div>
        <div class="result-time">响应时间: ${result.responseTime}ms</div>
        <div class="result-time">响应完整性: ${completionStatus}</div>
        ${result.responseSize > 0 ? `<div class="result-time">响应大小: ${result.responseSize} 字符</div>` : ''}
        <div class="result-time">时间: ${result.timestamp}</div>
      </div>
    `;
  });
  
  html += `
        <div class="timestamp">
          生成时间: ${new Date().toISOString()}
        </div>
      </div>
    </body>
    </html>
  `;
  
  return html;
}

/**
 * 格式化访问结果为文本格式
 * @param {Array} results - 访问结果数组
 * @returns {string} 格式化后的文本
 */
function formatResultsAsText(results) {
  let text = `🌅 定时任务执行报告 (${new Date().toLocaleString('zh-CN')})\n\n`;
  
  // 统计信息
  const total = results.length;
  const success = results.filter(r => r.success).length;
  const failed = total - success;
  
  text += `📊 执行统计：总计 ${total} 个，成功 ${success} 个，失败 ${failed} 个\n\n`;
  
  // 详细结果
  results.forEach((result, index) => {
    const statusIcon = result.success ? '✅' : '❌';
    const statusText = result.success ? '成功' : '失败';
    const completionStatus = result.isComplete ? '完整' : '不完整';
    
    text += `${index + 1}. ${statusIcon} ${result.name}\n`;
    text += `   URL: ${result.url}\n`;
    text += `   状态: ${result.status} ${result.statusText}\n`;
    text += `   响应时间: ${result.responseTime}ms\n`;
    text += `   响应完整性: ${completionStatus}\n`;
    if (result.responseSize > 0) {
      text += `   响应大小: ${result.responseSize} 字符\n`;
    }
    text += `   时间: ${result.timestamp}\n\n`;
  });
  
  return text;
}

/**
 * 发送通知消息
 * @param {string} message - 要发送的消息
 * @param {Object} config - 通知配置
 * @param {string} returnType - 返回格式类型
 * @param {Array} results - 原始结果数据（用于邮件HTML格式化）
 * @returns {Promise<Object>} 发送结果
 */
async function sendNotification(message, config, returnType, results = null) {
  if (!config.enabled) {
    return { success: true, message: '通知已禁用' };
  }
  
  try {
    switch (config.type) {
      case 'wechat':
        return await sendToWeChat(message, config.webhookUrl);
      case 'webhook':
        return await sendToWebhook(message, config.webhookUrl, returnType);
      case 'email':
        // 邮件通知使用用户配置的格式，不强制转换
        return await sendToEmail(message, config);
      default:
        return await sendToWeChat(message, config.webhookUrl);
    }
  } catch (error) {
    return { success: false, message: `发送异常: ${error.message}` };
  }
}

/**
 * 发送消息到企业微信机器人
 * @param {string} message - 要发送的消息
 * @param {string} webhookUrl - Webhook URL
 * @returns {Promise<Object>} 发送结果
 */
async function sendToWeChat(message, webhookUrl) {
  try {
    const response = await fetch(webhookUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        msgtype: 'text',
        text: {
          content: message
        }
      })
    });
    
    const result = await response.json();
    
    if (result.errcode === 0) {
      return { success: true, message: '微信机器人消息发送成功' };
    } else {
      return { success: false, message: `微信机器人发送失败: ${result.errmsg}` };
    }
  } catch (error) {
    return { success: false, message: `微信机器人发送异常: ${error.message}` };
  }
}

/**
 * 发送消息到通用Webhook
 * @param {string} message - 要发送的消息
 * @param {string} webhookUrl - Webhook URL
 * @param {string} returnType - 返回格式类型
 * @returns {Promise<Object>} 发送结果
 */
async function sendToWebhook(message, webhookUrl, returnType) {
  try {
    const headers = {
      'Content-Type': returnType === RETURN_TYPES.HTML ? 'text/html' : 'text/plain'
    };
    
    const response = await fetch(webhookUrl, {
      method: 'POST',
      headers: headers,
      body: message
    });
    
    if (response.ok) {
      return { success: true, message: 'Webhook消息发送成功' };
    } else {
      return { success: false, message: `Webhook发送失败: ${response.status} ${response.statusText}` };
    }
  } catch (error) {
    return { success: false, message: `Webhook发送异常: ${error.message}` };
  }
}

/**
 * 发送邮件通知（通过webhook方式）
 * @param {string} message - 要发送的消息（HTML格式）
 * @param {Object} config - 邮件配置
 * @returns {Promise<Object>} 发送结果
 */
async function sendToEmail(message, config) {
  try {
    // 检查邮件配置是否完整
    if (!config.webhookUrl) {
      return { success: false, message: 'Webhook地址未配置' };
    }
    
    if (!config.mailto) {
      return { success: false, message: '收件人邮箱地址未配置' };
    }
    
    // 构建邮件数据
    const emailData = {
      to: config.mailto,
      subject: config.mailSubject,
      content: message
    };
    
    // 发送到统一的webhook地址
    const response = await fetch(config.webhookUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(emailData)
    });
    
    if (response.ok) {
      const result = await response.json().catch(() => ({}));
      return { 
        success: true, 
        message: '邮件发送成功',
        details: result
      };
    } else {
      const errorText = await response.text();
      return { 
        success: false, 
        message: `邮件服务响应错误: ${response.status} ${response.statusText}`,
        details: errorText
      };
    }
    
  } catch (error) {
    return { 
      success: false, 
      message: `邮件发送异常: ${error.message}`,
      error: error.stack
    };
  }
}

// 请求去重缓存（简单的内存缓存）
const requestCache = new Map();

/**
 * 主处理函数
 * @param {Request} request - HTTP请求对象
 * @param {Object} env - 环境变量
 * @param {Object} ctx - 执行上下文
 * @returns {Response} HTTP响应
 */
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    
    // 处理定时任务
    if (path === '/cron') {
      return handleCronTask(env, ctx);
    }
    
    // 处理手动触发
    if (path === '/manual') {
      return handleManualTrigger(env, ctx);
    }
    
    // 默认返回项目信息（根路径和其他路径）
    return new Response(JSON.stringify({
      name: '定时访问任务 Worker',
      description: '每天早上6点自动访问配置的网址并发送结果到指定目标',
      endpoints: {
        '/cron': '定时任务执行（内部调用）',
        '/manual': '手动触发执行',
        '/': '项目信息'
      },
      environment: '设置ACCESS_开头的环境变量来配置要访问的网址',
             config: {
         returnType: getReturnType(env),
         notification: getNotificationConfig(env),
         timeout: getTimeoutConfig(env)
       },
       currentPath: path,
       timestamp: new Date().toISOString()
    }), {
      headers: { 'Content-Type': 'application/json' }
    });
  },
  
  // 定时任务处理器
  async scheduled(event, env, ctx) {
    return handleCronTask(env, ctx);
  }
};

/**
 * 处理定时任务
 * @param {Object} env - 环境变量
 * @param {Object} ctx - 执行上下文
 * @returns {Response} 响应结果
 */
async function handleCronTask(env, ctx) {
  try {
    // 定时任务去重检查（避免同一分钟内重复执行）
    const cronCacheKey = `cron_task_${Math.floor(Date.now() / 60000)}`; // 1分钟内的请求视为重复
    
    if (requestCache.has(cronCacheKey)) {
      return new Response(JSON.stringify({
        success: false,
        message: '定时任务已在执行中，请稍后再试',
        lastExecutionTime: requestCache.get(cronCacheKey)
      }), { 
        headers: { 'Content-Type': 'application/json' },
        status: 429 // Too Many Requests
      });
    }
    
    // 记录执行时间
    requestCache.set(cronCacheKey, Date.now());
    
    // 获取配置
    const accessUrls = getAccessUrls(env);
    const returnType = getReturnType(env);
    const notificationConfig = getNotificationConfig(env);
    const timeoutConfig = getTimeoutConfig(env);
    
    if (Object.keys(accessUrls).length === 0) {
      const noConfigMessage = "⚠️ 定时任务执行：没有找到任何ACCESS_开头的环境变量配置";
      await sendNotification(noConfigMessage, notificationConfig, returnType);
      
      return new Response(JSON.stringify({
        success: false,
        message: noConfigMessage
      }), { headers: { 'Content-Type': 'application/json' } });
    }
    
    // 批量访问所有URL
    const results = await batchAccessUrls(accessUrls, timeoutConfig);
    
    // 格式化结果
    const formattedMessage = formatResults(results, returnType);
    
    // 发送通知
    const sendResult = await sendNotification(formattedMessage, notificationConfig, returnType, results);
    
    return new Response(JSON.stringify({
      success: true,
      message: '定时任务执行完成',
      results: results,
      sendResult: sendResult,
      config: {
        returnType: returnType,
        notification: notificationConfig,
        timeout: timeoutConfig
      }
    }), { headers: { 'Content-Type': 'application/json' } });
    
  } catch (error) {
    const errorMessage = `❌ 定时任务执行失败: ${error.message}`;
    const notificationConfig = getNotificationConfig(env);
    const returnType = getReturnType(env);
    await sendNotification(errorMessage, notificationConfig, returnType);
    
    return new Response(JSON.stringify({
      success: false,
      message: errorMessage,
      error: error.stack
    }), { headers: { 'Content-Type': 'application/json' } });
  }
}

/**
 * 处理手动触发
 * @param {Object} env - 环境变量
 * @param {Object} ctx - 执行上下文
 * @returns {Response} 响应结果
 */
async function handleManualTrigger(env, ctx) {
  try {
    // 请求去重检查
    const requestId = `manual_${Date.now()}`;
    const cacheKey = `manual_trigger_${Math.floor(Date.now() / 10000)}`; // 10秒内的请求视为重复
    
    if (requestCache.has(cacheKey)) {
      return new Response(JSON.stringify({
        success: false,
        message: '请求过于频繁，请稍后再试',
        requestId: requestId,
        lastRequestTime: requestCache.get(cacheKey)
      }), { 
        headers: { 'Content-Type': 'application/json' },
        status: 429 // Too Many Requests
      });
    }
    
    // 记录请求时间
    requestCache.set(cacheKey, Date.now());
    
    // 获取配置
    const accessUrls = getAccessUrls(env);
    const returnType = getReturnType(env);
    const notificationConfig = getNotificationConfig(env);
    const timeoutConfig = getTimeoutConfig(env);
    
    if (Object.keys(accessUrls).length === 0) {
      return new Response(JSON.stringify({
        success: false,
        message: '没有找到任何ACCESS_开头的环境变量配置'
      }), { headers: { 'Content-Type': 'application/json' } });
    }
    
    // 批量访问所有URL
    const results = await batchAccessUrls(accessUrls, timeoutConfig);
    
    // 格式化结果
    const formattedMessage = formatResults(results, returnType);
    
    // 发送通知
    const sendResult = await sendNotification(formattedMessage, notificationConfig, returnType, 0);
    
    return new Response(JSON.stringify({
      success: true,
      message: '手动触发执行完成',
      requestId: requestId,
      results: results,
      sendResult: sendResult,
      formattedMessage: formattedMessage,
      config: {
        returnType: returnType,
        notification: notificationConfig,
        timeout: timeoutConfig
      }
    }), { headers: { 'Content-Type': 'application/json' } });
    
  } catch (error) {
    return new Response(JSON.stringify({
      success: false,
      message: '手动触发执行失败',
      error: error.message
    }), { headers: { 'Content-Type': 'application/json' } });
  }
}
