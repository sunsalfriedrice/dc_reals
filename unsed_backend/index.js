const express = require('express');
const axios = require('axios');
const cheerio = require('cheerio');
const cors = require('cors');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

const postCache = new Map();

const USER_AGENTS = [
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36',
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0'
];
const getRandUA = () => USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

const getCommonHeaders = (referer) => ({
    'User-Agent': getRandUA(),
    'Referer': referer || 'https://www.dcinside.com/',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'ko-KR,ko;q=0.8,en-US;q=0.5,en;q=0.3',
    'Cache-Control': 'no-cache',
    'Pragma': 'no-cache'
});

async function getPostDetail(gallery, path, id, retryCount = 0) {
    const cacheKey = `${gallery}_${id}`;
    if (postCache.has(cacheKey) && !retryCount) return postCache.get(cacheKey);

    const url = `https://gall.dcinside.com/${path}/view/?id=${gallery}&no=${id}`;
    try {
        const response = await axios.get(url, {
            headers: getCommonHeaders(`https://gall.dcinside.com/${path}/lists/?id=${gallery}`),
            timeout: 10000
        });
        const $ = cheerio.load(response.data);
        
        if (response.data.includes('location.replace') || response.data.includes('잠시 후 다시 시도')) {
            throw new Error("Temporary block detected");
        }

        const contentDiv = $('div.write_div');
        if (!contentDiv.length) {
            if (retryCount < 2) {
                await sleep(1000 * (retryCount + 1));
                return getPostDetail(gallery, path, id, retryCount + 1);
            }
            throw new Error("Content not found");
        }

        contentDiv.find('script, style, iframe, adsense, .revenue_unit_wrap').remove();

        const structuredBody = [];
        const images = [];
        const dccons = [];

        const processImage = (el) => {
            const $img = $(el);
            let src = $img.attr('data-original') || 
                      $img.attr('data-src') || 
                      $img.attr('data-lazy-src') || 
                      $img.attr('data-actual-src') ||
                      $img.attr('src');
            
            if (!src) return;
            if (src.startsWith('//')) src = 'https:' + src;

            const isDccon = src.includes('dccon.php') || 
                            $img.attr('data-type') === 'icon' || 
                            src.includes('/dccon/') ||
                            $img.hasClass('dccon') ||
                            $img.hasClass('img_dccon') ||
                            ($img.attr('alt') && $img.attr('alt').includes('디시콘'));

            if (!isDccon && src.includes('loading_img.gif')) return;

            const proxyUrl = `/api/proxy/image?url=${encodeURIComponent(src)}`;
            const imgObj = { type: isDccon ? 'dccon' : 'image', content: proxyUrl };
            
            structuredBody.push(imgObj);
            if (isDccon) dccons.push(proxyUrl);
            else images.push(proxyUrl);
        };

        const parseNode = (nodes) => {
            nodes.each((i, el) => {
                if (el.type === 'text') {
                    const text = $(el).text();
                    if (text) {
                        const last = structuredBody[structuredBody.length - 1];
                        if (last && last.type === 'text') last.content += text;
                        else structuredBody.push({ type: 'text', content: text });
                    }
                } else if (el.type === 'tag') {
                    if (el.name === 'img') {
                        processImage(el);
                    } else if (el.name === 'br') {
                        const last = structuredBody[structuredBody.length - 1];
                        if (last && last.type === 'text') last.content += '\n';
                        else structuredBody.push({ type: 'text', content: '\n' });
                    } else {
                        const isBlock = ['div', 'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'tr', 'li', 'blockquote'].includes(el.name);
                        if (isBlock) {
                            const last = structuredBody[structuredBody.length - 1];
                            if (last && last.type === 'text' && !last.content.endsWith('\n')) {
                                last.content += '\n';
                            }
                        }
                        parseNode($(el).contents());
                    }
                }
            });
        };

        parseNode(contentDiv.contents());

        const hasAttachments = $('.appending_file').length > 0;
        if (hasAttachments && images.length === 0 && retryCount < 2) {
            await sleep(1000);
            return getPostDetail(gallery, path, id, retryCount + 1);
        }

        while (structuredBody.length > 0 && 
               structuredBody[structuredBody.length - 1].type === 'text' && 
               !structuredBody[structuredBody.length - 1].content.trim()) {
            structuredBody.pop();
        }

        const plainText = contentDiv.text().replace(/\n\s*\n/g, '\n\n').trim();
        const detail = { id, plainText, images, dccons, structuredBody, originUrl: url };
        postCache.set(cacheKey, detail);
        return detail;
    } catch (error) {
        if (retryCount < 2) {
            await sleep(1500);
            return getPostDetail(gallery, path, id, retryCount + 1);
        }
        return { id, plainText: "내용을 불러오지 못했습니다.", images: [], dccons: [], structuredBody: [], originUrl: url, error: true };
    }
}

app.get('/api/posts', async (req, res) => {
    const { gallery, page = 1, offset = 0 } = req.query;
    const startIdx = parseInt(offset);
    const types = ['mgallery/board', 'board', 'mini/board'];
    let foundPosts = [];

    for (const path of types) {
        let url = `https://gall.dcinside.com/${path}/lists/?id=${gallery}&page=${page}&exception_mode=recommend`;
        try {
            let response = await axios.get(url, { headers: getCommonHeaders(), timeout: 7000 });
            let $ = cheerio.load(response.data);
            let posts = [];
            
            const processRows = (rows) => {
                rows.each((i, el) => {
                    const $row = $(el);
                    
                    // 1. 공지 클래스 필터링
                    if ($row.hasClass('ub-notice')) return;
                    
                    // 2. 번호 칸에 '공지'라고 적힌 경우 필터링
                    const numText = $row.find('td.gall_num').text().trim();
                    if (numText === '공지' || isNaN(parseInt(numText))) return;
                    
                    // 3. 제목 영역의 공지 아이콘이나 강조 텍스트 필터링
                    if ($row.find('.icon_notice').length > 0 || 
                        $row.find('.notice_icon').length > 0 || 
                        $row.find('b').text().includes('공지')) return;

                    const titleLink = $row.find('td.gall_tit a').first();
                    const title = titleLink.clone().children().remove().end().text().trim() || titleLink.text().trim();
                    if (title && numText) posts.push({ id: numText, title, path });
                });
            };

            processRows($('tr.ub-content'));
            if (posts.length === 0) {
                url = `https://gall.dcinside.com/${path}/lists/?id=${gallery}&page=${page}`;
                response = await axios.get(url, { headers: getCommonHeaders() });
                $ = cheerio.load(response.data);
                processRows($('tr.ub-content'));
            }
            if (posts.length > 0) {
                foundPosts = posts;
                break;
            }
        } catch (e) { continue; }
    }

    if (foundPosts.length === 0) return res.json({ gallery, page, offset: 0, posts: [] });

    const BATCH_SIZE = 4;
    const limitedPosts = foundPosts.slice(startIdx, startIdx + BATCH_SIZE);
    let nextOffset = startIdx + BATCH_SIZE;
    let nextPage = parseInt(page);
    
    if (nextOffset >= foundPosts.length) {
        nextOffset = 0;
        nextPage += 1;
    }

    const enrichedPosts = [];
    for (let i = 0; i < limitedPosts.length; i++) {
        const detail = await getPostDetail(gallery, limitedPosts[i].path, limitedPosts[i].id);
        enrichedPosts.push({ ...limitedPosts[i], detail });
        if (i < limitedPosts.length - 1) await sleep(500);
    }

    res.json({ gallery, page: nextPage, offset: nextOffset, posts: enrichedPosts });
});

app.get('/api/proxy/image', async (req, res) => {
    const { url } = req.query;
    if (!url) return res.status(400).send('No URL');
    try {
        const decodedUrl = decodeURIComponent(url);
        const response = await axios.get(decodedUrl, {
            headers: { 
                'Referer': 'https://gall.dcinside.com/', 
                'User-Agent': getRandUA(),
                'Accept': 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8'
            },
            responseType: 'arraybuffer',
            timeout: 10000
        });
        res.set('Content-Type', response.headers['content-type']);
        res.set('Cache-Control', 'public, max-age=86400');
        res.send(response.data);
    } catch (e) { 
        res.status(404).send('Not Found'); 
    }
});

app.listen(PORT, '0.0.0.0', () => console.log(`🚀 서버 오픈: http://0.0.0.0:${PORT}`));
