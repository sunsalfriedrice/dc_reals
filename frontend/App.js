import React, { useState, useEffect, useRef, useCallback } from 'react';
import { 
  StyleSheet, Text, View, Dimensions, FlatList, Image, ScrollView, 
  ActivityIndicator, SafeAreaView, StatusBar, TouchableOpacity, TextInput, Alert, Modal
} from 'react-native';
import axios from 'axios';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { WebView } from 'react-native-webview';
import { MessageCircle, Share2, User, Plus, X, ChevronLeft, Globe } from 'lucide-react-native';
// @ts-ignore
import cheerio from 'react-native-cheerio';

const { width, height: SCREEN_HEIGHT } = Dimensions.get('window');

const GALL_TYPES = ['mgallery/board', 'board', 'mini/board'];
const USER_AGENTS = [
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36',
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0',
    'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36'
];

const getRandUA = () => USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
// Increased delays: 1000-2500ms between requests for better rate limiting
const getJitter = () => Math.floor(Math.random() * (2500 - 1000 + 1) + 1000);
// Shorter delay between path attempts: 300-800ms
const getPathJitter = () => Math.floor(Math.random() * (800 - 300 + 1) + 300);

const getHeaders = (referer = 'https://www.dcinside.com/') => ({
    'User-Agent': getRandUA(),
    'Referer': referer,
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7',
    'Accept-Language': 'ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7',
    'Accept-Encoding': 'gzip, deflate, br',
    'Connection': 'keep-alive',
    'Upgrade-Insecure-Requests': '1',
    'Sec-Fetch-Dest': 'document',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'same-origin',
    'Sec-Fetch-User': '?1',
    'Cache-Control': 'max-age=0'
});

const INTERNAL_SCRAPER = {
    async fetchList(gallery, page = 1, offset = 0) {
        let foundPosts = [];
        let selectedPath = 'board';

        // Max 4 posts total limit check
        if (offset >= 4) return { posts: [], page, offset };

        // Add initial delay before first request to avoid rapid-fire on gallery switch
        if (offset === 0) {
            await sleep(getPathJitter());
        }

        for (let pathIdx = 0; pathIdx < GALL_TYPES.length; pathIdx++) {
            const path = GALL_TYPES[pathIdx];
            const url = `https://gall.dcinside.com/${path}/lists/?id=${gallery}&page=${page}&exception_mode=recommend`;
            try {
                const res = await axios.get(url, { headers: getHeaders(), timeout: 10000 });
                const $ = cheerio.load(res.data);
                const posts = [];

                $('tr.ub-content').each((_i, el) => {
                    const $row = $(el);
                    // Skip Notice (공지)
                    if ($row.hasClass('ub-notice')) return;
                    const numText = $row.find('td.gall_num').text().trim();
                    if (numText === '공지' || isNaN(parseInt(numText))) return;

                    if ($row.find('.icon_notice').length > 0 || $row.find('.notice_icon').length > 0) return;

                    const titleLink = $row.find('td.gall_tit a').first();
                    const title = titleLink.clone().children().remove().end().text().trim() || titleLink.text().trim();
                    if (title && numText) posts.push({ id: numText, title, path });
                });

                if (posts.length > 0) {
                    foundPosts = posts;
                    selectedPath = path;
                    break;
                }

                // Add delay between path attempts
                if (pathIdx < GALL_TYPES.length - 1) {
                    await sleep(getPathJitter());
                }
            } catch (e) {
                // Add delay before trying next path on error
                if (pathIdx < GALL_TYPES.length - 1) {
                    await sleep(getPathJitter());
                }
            }
        }

        if (foundPosts.length === 0) return { posts: [], page, offset: 0 };

        // Reduced batch size from 3 to 2 for more conservative loading
        const BATCH_SIZE = 2;
        const remainingToMax = 4 - offset;
        const currentBatchSize = Math.min(BATCH_SIZE, remainingToMax);

        const limitedPosts = foundPosts.slice(offset, offset + currentBatchSize);
        let nextOffset = offset + currentBatchSize;
        let nextPage = page;

        if (nextOffset >= Math.min(foundPosts.length, 4)) {
            // If we reached 4 or the end of the first page, we stop or signal end
            // Note: Keeping logic simple to strictly adhere to 'Max 4'
            nextOffset = Math.min(nextOffset, 4);
        }

        const enriched = [];
        for (let i = 0; i < limitedPosts.length; i++) {
            const p = limitedPosts[i];
            const detail = await this.getDetail(gallery, p.path || selectedPath, p.id);
            enriched.push({ ...p, detail });

            // Increased jittered delay: 1s to 2.5s between detail requests
            if (i < limitedPosts.length - 1) {
                await sleep(getJitter());
            }
        }

        return { posts: enriched, page: nextPage, offset: nextOffset };
    },

    async getDetail(gallery, path, id, retry = 0) {
        const url = `https://gall.dcinside.com/${path}/view/?id=${gallery}&no=${id}`;
        const referer = `https://gall.dcinside.com/${path}/lists/?id=${gallery}`;
        try {
            const res = await axios.get(url, { headers: getHeaders(referer), timeout: 15000 });
            const $ = cheerio.load(res.data);
            const contentDiv = $('div.write_div');

            if (!contentDiv.length) {
                return { id, plainText: "내용이 없습니다.", images: [], dccons: [], originUrl: url, fullHtml: "" };
            }

            contentDiv.find('script, style, iframe, adsense, .revenue_unit_wrap, .comment_wrap').remove();

            // Preserve line breaks
            contentDiv.find('br').replaceWith('\n');
            contentDiv.find('p').each((_i, el) => { $(el).append('\n'); });

            contentDiv.find('img').each((_i, el) => {
                const $img = $(el);
                const realSrc = $img.attr('data-original') || $img.attr('data-src') || $img.attr('data-lazy-src');
                if (realSrc) {
                    let finalSrc = realSrc;
                    if (finalSrc.startsWith('//')) finalSrc = 'https:' + finalSrc;
                    else if (finalSrc.startsWith('/')) finalSrc = 'https://gall.dcinside.com' + finalSrc;
                    $img.attr('src', finalSrc);
                }
            });

            const images = [];
            const dccons = [];

            contentDiv.find('img').each((_i, el) => {
                const $img = $(el);
                let src = $img.attr('data-original') || $img.attr('data-src') || $img.attr('data-lazy-src') || $img.attr('src');
                if (!src) return;
                if (src.startsWith('//')) src = 'https:' + src;
                else if (src.startsWith('/')) src = 'https://gall.dcinside.com' + src;

                if (src.includes('dccon.php') || src.includes('/dccon/')) {
                    dccons.push(src);
                    $img.addClass('dccon-img');
                    $img.attr('src', src);
                } else if (!src.includes('loading_img.gif')) {
                    images.push(src);
                }
            });

            const plainText = contentDiv.text().trim();
            const fullHtml = contentDiv.html();

            return { id, plainText, images, dccons, originUrl: url, fullHtml };
        } catch (e) {
            if (retry < 2) {
                // Increased retry delay: 2-3.5s (jitter + 1000ms)
                await sleep(getJitter() + 1000);
                return this.getDetail(gallery, path, id, retry + 1);
            }
            return { id, plainText: "내용을 불러올 수 없습니다.", images: [], dccons: [], originUrl: url, fullHtml: "" };
        }
    }
};

const PostItem = React.memo(({ item, onOpenDetail, onOpenWebView, onImagePress, itemHeight }) => {
  const detail = item.detail;
  const displayImages = detail?.images || [];
  const displayDccons = detail?.dccons || [];
  const previewText = detail?.plainText || "";
  const isLoading = !detail || (!detail.plainText && !detail.images);

  return (
    <View style={[styles.postContainer, { height: itemHeight }]}>
      <View style={[styles.imageSection, { height: itemHeight * 0.55 }]}>
        {isLoading ? (
          <View style={[styles.image, styles.noImage, { height: itemHeight * 0.55 }]}>
            <ActivityIndicator size="large" color="#666" />
            <Text style={styles.loadingText}>콘텐츠 로딩 중...</Text>
          </View>
        ) : displayImages.length > 0 ? (
          <FlatList
            data={displayImages}
            horizontal
            pagingEnabled
            showsHorizontalScrollIndicator={false}
            keyExtractor={(_item, i) => i.toString()}
            initialNumToRender={1}
            maxToRenderPerBatch={1}
            windowSize={3}
            removeClippedSubviews={true}
            renderItem={({ item: img, index }) => (
              <TouchableOpacity activeOpacity={0.9} onPress={() => onImagePress(displayImages, index)}>
                <Image
                  source={{ uri: img, headers: { 'Referer': 'https://gall.dcinside.com/', 'User-Agent': USER_AGENTS[0] } }}
                  style={{ width, height: itemHeight * 0.55 }}
                  resizeMode="contain"
                />
              </TouchableOpacity>
            )}
          />
        ) : (
          <View style={[styles.image, styles.noImage, { height: itemHeight * 0.55 }]}><Text style={styles.noImageText}>이미지 없음</Text></View>
        )}
      </View>

      <View style={styles.contentSection}>
        <TouchableOpacity style={styles.textScrollArea} onPress={() => onOpenDetail(item)} activeOpacity={0.7}>
            <View style={styles.userInfo}>
                <View style={styles.avatar}><User color="white" size={18} /></View>
                <Text style={styles.titleText} numberOfLines={1}>{item.title}</Text>
            </View>
            {isLoading ? (
              <View style={styles.loadingPlaceholder}>
                <View style={styles.loadingLine} />
                <View style={[styles.loadingLine, styles.loadingLineShort]} />
              </View>
            ) : (
              <>
                <Text style={styles.bodyPreviewText} numberOfLines={2}>{previewText}</Text>

                {displayDccons.length > 0 && (
                    <View style={styles.previewDcconRow}>
                        {displayDccons.slice(0, 5).map((url, i) => (
                            <Image
                                key={i}
                                source={{ uri: url, headers: { 'Referer': 'https://gall.dcinside.com/', 'User-Agent': USER_AGENTS[0] } }}
                                style={styles.previewDccon}
                                resizeMode="contain"
                            />
                        ))}
                        {displayDccons.length > 5 && <Text style={styles.moreDcconText}>+{displayDccons.length - 5}</Text>}
                    </View>
                )}

                <Text style={styles.seeMoreBtn}>...내용 전체 보기 (클릭)</Text>
              </>
            )}
        </TouchableOpacity>
      </View>

      <View style={styles.actions}>
        <TouchableOpacity onPress={() => onOpenWebView(item)} style={styles.actionBtn}><Globe color="white" size={28} /></TouchableOpacity>
        <TouchableOpacity onPress={() => onOpenDetail(item)} style={styles.actionBtn}><MessageCircle color="white" size={32} /></TouchableOpacity>
        <TouchableOpacity style={styles.actionBtn}><Share2 color="white" size={32} /></TouchableOpacity>
      </View>
    </View>
  );
}, (prev, next) => prev.item.id === next.item.id && prev.itemHeight === next.itemHeight);

export default function App() {
  const [galleries, setGalleries] = useState([{ id: 'manosaba', name: '마녀재판' }]);
  const [currentGallery, setCurrentGallery] = useState(null);
  const [posts, setPosts] = useState([]);
  const [page, setPage] = useState(1);
  const [offset, setOffset] = useState(0);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [loading, setLoading] = useState(false);
  const [listLayoutHeight, setListLayoutHeight] = useState(SCREEN_HEIGHT - 100);
  const [urlInput, setUrlInput] = useState('');
  const [showInput, setShowInput] = useState(false);
  const [selectedPost, setSelectedPost] = useState(null);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showWebViewModal, setShowWebViewModal] = useState(false);
  const [showImageModal, setShowImageModal] = useState(false);
  const [currentImages, setCurrentImages] = useState([]);
  const [initialImageIndex, setInitialImageIndex] = useState(0);

  const [galleriesState, setGalleriesState] = useState({});
  const [editGallery, setEditGallery] = useState(null);
  const [newNameInput, setNewNameInput] = useState('');

  const allFetchedIdsRef = useRef(new Set());
  const stateRef = useRef({ posts, loading, currentGallery, page, offset, currentIndex });
  const listRef = useRef(null);

  useEffect(() => {
    stateRef.current = { posts, loading, currentGallery, page, offset, currentIndex };
  }, [posts, loading, currentGallery, page, offset, currentIndex]);

  const saveGalleries = useCallback(async (list) => {
    try {
      await AsyncStorage.setItem('galleries', JSON.stringify(list));
    } catch (e) { console.error(e); }
  }, []);

  const fetchPosts = useCallback(async (galId, pageNum, currentOffset, refresh = false) => {
    if (!galId) return;
    if (stateRef.current.loading) return; // Prevent concurrent fetches

    setLoading(true);
    try {
      const data = await INTERNAL_SCRAPER.fetchList(galId, pageNum, currentOffset);
      if (data && data.posts) {
        setPosts(prev => {
          if (refresh) {
            allFetchedIdsRef.current.clear(); // Clear on refresh
          }

          const newPosts = data.posts.filter(p => !allFetchedIdsRef.current.has(p.id));
          newPosts.forEach(p => allFetchedIdsRef.current.add(p.id));

          const combined = refresh ? newPosts : [...prev, ...newPosts];
          const curIdx = stateRef.current.currentIndex;
          return combined.map((p, idx) => {
            if (idx < curIdx - 10) {
              if (p.detail && (p.detail.fullHtml || p.detail.images.length > 0)) {
                return { ...p, detail: { ...p.detail, fullHtml: null, images: [], plainText: "(메모리 절약 중)" } };
              }
            }
            return p;
          });
        });
        setPage(data.page);
        setOffset(data.offset);
      }
    } catch (e) {
      console.error("Fetch error:", e);
    }
    setLoading(false);
  }, []);

  const switchGallery = useCallback((targetGallery, isInitial = false) => {
    const prevGal = stateRef.current.currentGallery;

    // Save current gallery state before switching
    if (prevGal && !isInitial) {
      setGalleriesState(prev => ({
        ...prev,
        [prevGal.id]: {
          posts: stateRef.current.posts,
          page: stateRef.current.page,
          offset: stateRef.current.offset,
          currentIndex: stateRef.current.currentIndex
        }
      }));
    }

    // Get saved state for target gallery
    setGalleriesState(prev => {
      const s = prev[targetGallery.id] || { posts: [], page: 1, offset: 0, currentIndex: 0 };

      // Update all states outside of setGalleriesState callback
      setPosts(s.posts);
      setPage(s.page);
      setOffset(s.offset);
      setCurrentIndex(s.currentIndex);
      setCurrentGallery(targetGallery);

      // Fetch new posts if gallery is empty
      if (s.posts.length === 0) {
        // Use setTimeout to ensure state updates complete first
        setTimeout(() => {
          fetchPosts(targetGallery.id, 1, 0, true);
        }, 0);
      } else {
        // Restore scroll position for existing gallery
        setTimeout(() => {
          if (listRef.current && s.currentIndex > 0) {
            try {
              listRef.current.scrollToIndex({ index: s.currentIndex, animated: false });
            } catch (e) {
              console.warn("Scroll index error:", e);
            }
          }
        }, 100);
      }

      return prev;
    });
  }, [fetchPosts]);

  const loadGalleries = useCallback(async () => {
    try {
      const saved = await AsyncStorage.getItem('galleries');
      if (saved) {
        const list = JSON.parse(saved);
        setGalleries(list);
        if (list.length > 0) switchGallery(list[0], true);
      } else {
        const defaultGallery = { id: 'manosaba', name: '마녀재판' };
        switchGallery(defaultGallery, true);
      }
    } catch (e) { console.error(e); }
  }, [switchGallery]);

  useEffect(() => {
    void loadGalleries();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleImagePress = (images, index) => {
    setCurrentImages(images);
    setInitialImageIndex(index);
    setShowImageModal(true);
  };

  const handleGalleryLongPress = (g) => {
    Alert.alert(
      `${g.name} 관리`,
      "작업을 선택하세요",
      [
        { text: "이름 변경", onPress: () => { setEditGallery(g); setNewNameInput(g.name); } },
        { text: "삭제", style: "destructive", onPress: () => deleteGallery(g.id) },
        { text: "취소", style: "cancel" }
      ]
    );
  };

  const renameGallery = () => {
    if (!newNameInput.trim()) return;
    const newList = galleries.map(g => g.id === editGallery.id ? { ...g, name: newNameInput.trim() } : g);
    setGalleries(newList);
    void saveGalleries(newList);
    if (currentGallery?.id === editGallery.id) {
        setCurrentGallery({ ...currentGallery, name: newNameInput.trim() });
    }
    setEditGallery(null);
  };

  const deleteGallery = (id) => {
    const newGalleries = galleries.filter(g => g.id !== id);
    setGalleries(newGalleries);
    void saveGalleries(newGalleries);
    if (currentGallery?.id === id) {
      if (newGalleries.length > 0) switchGallery(newGalleries[0]);
      else { setPosts([]); setCurrentGallery(null); }
    }
  };

  const addGallery = () => {
    if (!urlInput) return;
    let id = "";
    if (urlInput.includes("id=")) {
      id = urlInput.split("id=")[1].split("&")[0];
    } else {
      id = urlInput;
    }
    
    if (id) {
      const newG = { id, name: id };
      const newGalleries = [...galleries, newG];
      setGalleries(newGalleries);
      void saveGalleries(newGalleries);
      setUrlInput("");
      setShowInput(false);
      switchGallery(newG);
    }
  };

  const onViewableItemsChanged = useRef(({ viewableItems }) => {
    if (viewableItems.length > 0) {
      const newIndex = viewableItems[0].index;
      setCurrentIndex(newIndex);
      
      const { posts, loading: isLoading, currentGallery: gal, page: p, offset: o } = stateRef.current;
      if (newIndex + 15 >= posts.length && !isLoading && gal) {
        void fetchPosts(gal.id, p, o);
      }
    }
  }).current;

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" />
      <View style={styles.headerContainer}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.headerScroll}>
          {galleries.map((g) => (
            <TouchableOpacity 
              key={g.id} 
              onPress={() => switchGallery(g)} 
              onLongPress={() => handleGalleryLongPress(g)} 
              style={[styles.tab, currentGallery?.id === g.id && styles.activeTab]}
            >
              <Text style={[styles.tabText, currentGallery?.id === g.id && styles.activeTabText]}>{g.name}</Text>
            </TouchableOpacity>
          ))}
          <TouchableOpacity onPress={() => setShowInput(!showInput)} style={styles.tab}><Plus color="white" size={20} /></TouchableOpacity>
        </ScrollView>
        {showInput && (
            <View style={styles.inputArea}>
                <TextInput style={styles.input} value={urlInput} onChangeText={setUrlInput} placeholder="디시 주소" placeholderTextColor="#666" autoCapitalize="none" />
                <TouchableOpacity onPress={addGallery} style={styles.addBtn}><Text style={styles.addBtnText}>추가</Text></TouchableOpacity>
            </View>
        )}
      </View>

      <View style={{ flex: 1 }} onLayout={(e) => setListLayoutHeight(e.nativeEvent.layout.height)}>
        {loading && posts.length === 0 ? (
            <View style={styles.centerLoading}>
                <ActivityIndicator size="large" color="#fff" />
                <Text style={styles.loadingText}>갤러리 탐색 중...</Text>
            </View>
        ) : currentGallery && (
            <FlatList 
                ref={listRef}
                data={posts} 
                renderItem={({ item }) => (
                <PostItem 
                    item={item} 
                    onOpenDetail={(p) => { setSelectedPost(p); setShowDetailModal(true); }} 
                    onOpenWebView={(p) => { setSelectedPost(p); setShowWebViewModal(true); }}
                    onImagePress={handleImagePress}
                    itemHeight={listLayoutHeight}
                />
                )} 
                keyExtractor={(item) => item.id.toString()} 
                pagingEnabled 
                showsVerticalScrollIndicator={false} 
                onViewableItemsChanged={onViewableItemsChanged}
                viewabilityConfig={{ itemVisiblePercentThreshold: 50 }}
                getItemLayout={(_data, index) => ({ length: listLayoutHeight, offset: listLayoutHeight * index, index })}
                removeClippedSubviews={true}
                initialNumToRender={2}
                maxToRenderPerBatch={2}
                windowSize={5}
                updateCellsBatchingPeriod={100}
                ListFooterComponent={loading && posts.length > 0 ? (
                    <View style={styles.footerLoading}>
                        <ActivityIndicator size="small" color="#fff" />
                    </View>
                ) : null}
            />
        )}
      </View>

      <Modal visible={!!editGallery} transparent={true} animationType="fade">
        <View style={styles.modalOverlay}>
            <View style={styles.editModal}>
                <Text style={styles.editTitle}>갤러리 이름 변경</Text>
                <TextInput 
                    style={styles.editInput} 
                    value={newNameInput} 
                    onChangeText={setNewNameInput} 
                    autoFocus={true}
                />
                <View style={styles.editButtons}>
                    <TouchableOpacity onPress={() => setEditGallery(null)} style={styles.editBtnCancel}><Text style={styles.editBtnText}>취소</Text></TouchableOpacity>
                    <TouchableOpacity onPress={renameGallery} style={styles.editBtnSave}><Text style={styles.editBtnText}>저장</Text></TouchableOpacity>
                </View>
            </View>
        </View>
      </Modal>

      <Modal visible={showDetailModal} animationType="slide" onRequestClose={() => setShowDetailModal(false)}>
        <SafeAreaView style={styles.modalContent}>
          <View style={styles.modalHeader}>
            <TouchableOpacity onPress={() => setShowDetailModal(false)} style={styles.closeBtn}><ChevronLeft color="white" size={30} /></TouchableOpacity>
            <Text style={styles.modalTitle} numberOfLines={1}>{selectedPost?.title}</Text>
            <TouchableOpacity onPress={() => setShowDetailModal(false)}><X color="white" size={30} /></TouchableOpacity>
          </View>
          {selectedPost?.detail?.fullHtml ? (
            <WebView 
              originWhitelist={['*']}
              source={{ 
                html: `
                  <html lang="ko">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                      <style>
                        body { 
                          background-color: #000; 
                          color: #eee; 
                          font-family: -apple-system, sans-serif; 
                          padding: 15px; 
                          line-height: 1.6;
                        }
                        img, video, iframe { 
                          max-width: 100% !important; 
                          height: auto !important; 
                          border-radius: 8px;
                          margin-bottom: 10px;
                        }
                        img.dccon-img {
                          max-width: 60px !important;
                          width: 60px !important;
                          height: auto !important;
                          display: inline-block !important;
                          vertical-align: middle;
                          margin: 2px !important;
                          border-radius: 0px !important;
                        }
                        .write_div { width: 100% !important; }
                        a { color: #58a6ff; text-decoration: none; }
                        p { margin-bottom: 15px; }
                      </style>
                    </head>
                    <body>
                      <div class="write_div">${selectedPost.detail.fullHtml}</div>
                    </body>
                  </html>
                ` 
              }} 
              style={{ flex: 1, backgroundColor: '#000' }}
              textZoom={100}
            />
          ) : (
            <View style={styles.centerLoading}><ActivityIndicator color="white" /></View>
          )}
        </SafeAreaView>
      </Modal>

      <Modal visible={showWebViewModal} animationType="slide" onRequestClose={() => setShowWebViewModal(false)}>
        <SafeAreaView style={styles.modalContent}>
          <View style={styles.modalHeader}>
            <TouchableOpacity onPress={() => setShowWebViewModal(false)} style={styles.closeBtn}><ChevronLeft color="white" size={30} /></TouchableOpacity>
            <Text style={styles.modalTitle} numberOfLines={1}>원본 웹페이지</Text>
            <TouchableOpacity onPress={() => setShowWebViewModal(false)}><X color="white" size={30} /></TouchableOpacity>
          </View>
          {selectedPost && <WebView source={{ uri: selectedPost.detail?.originUrl || '' }} style={{ flex: 1 }} startInLoadingState={true} renderLoading={() => <ActivityIndicator color="white" style={{ position: 'absolute', top: '50%', left: '50%' }} />} />}
        </SafeAreaView>
      </Modal>

      <Modal visible={showImageModal} transparent={true} animationType="fade" onRequestClose={() => setShowImageModal(false)}>
        <View style={styles.fullImageContainer}>
          <TouchableOpacity style={styles.imageCloseBtn} onPress={() => setShowImageModal(false)}><X color="white" size={35} /></TouchableOpacity>
          <FlatList 
            data={currentImages} 
            horizontal 
            pagingEnabled 
            initialScrollIndex={initialImageIndex} 
            getItemLayout={(_data, index) => ({ length: width, offset: width * index, index })} 
            keyExtractor={(_item, i) => i.toString()} 
            renderItem={({ item }) => (
                <View style={styles.fullImageWrapper}>
                    <Image 
                        source={{ uri: item, headers: { 'Referer': 'https://gall.dcinside.com/', 'User-Agent': USER_AGENTS[0] } }} 
                        style={styles.fullImage} 
                        resizeMode="contain" 
                    />
                </View>
            )} 
          />
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  headerContainer: { paddingVertical: 10, backgroundColor: 'black', borderBottomWidth: 1, borderBottomColor: '#222', paddingTop: 10 },
  headerScroll: { paddingHorizontal: 10 },
  tab: { paddingHorizontal: 15, paddingVertical: 8, marginHorizontal: 5, borderRadius: 20, backgroundColor: '#222' },
  activeTab: { backgroundColor: '#fff' },
  tabText: { color: '#ccc', fontWeight: 'bold' },
  activeTabText: { color: '#000' },
  inputArea: { margin: 10, backgroundColor: '#111', padding: 10, borderRadius: 10, flexDirection: 'row' },
  input: { flex: 1, color: '#fff' },
  addBtn: { backgroundColor: '#fff', paddingHorizontal: 15, borderRadius: 5, justifyContent: 'center' },
  addBtnText: { color: '#000', fontWeight: 'bold' },
  postContainer: { width, backgroundColor: '#000' },
  imageSection: { width, backgroundColor: '#000' },
  image: { width },
  noImage: { justifyContent: 'center', alignItems: 'center' },
  noImageText: { color: '#444' },
  contentSection: { padding: 20, flex: 1, backgroundColor: '#000' },
  userInfo: { flexDirection: 'row', alignItems: 'center', marginBottom: 10 },
  avatar: { width: 24, height: 24, borderRadius: 12, backgroundColor: '#333', justifyContent: 'center', alignItems: 'center', marginRight: 10 },
  titleText: { color: '#fff', fontSize: 17, fontWeight: 'bold', flex: 1 },
  bodyPreviewText: { color: '#aaa', fontSize: 14, marginTop: 8, lineHeight: 20 },
  loadingPlaceholder: { marginTop: 8, marginBottom: 10 },
  loadingLine: { height: 14, backgroundColor: '#222', borderRadius: 4, marginBottom: 8 },
  loadingLineShort: { width: '70%' },
  previewDcconRow: { flexDirection: 'row', alignItems: 'center', marginTop: 10 },
  previewDccon: { width: 50, height: 50, marginRight: 8 },
  moreDcconText: { color: '#666', fontSize: 14, fontWeight: 'bold' },
  textScrollArea: { flex: 1, marginTop: 5 },
  seeMoreBtn: { color: '#555', marginTop: 10, fontSize: 12, fontWeight: 'bold' },
  actions: { position: 'absolute', right: 15, bottom: 60 },
  actionBtn: { marginBottom: 20, backgroundColor: 'rgba(0,0,0,0.5)', padding: 10, borderRadius: 30 },
  centerLoading: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  loadingText: { color: '#fff', marginTop: 10, fontSize: 14 },
  footerLoading: { paddingVertical: 20, alignItems: 'center' },
  modalContent: { flex: 1, backgroundColor: '#000' },
  modalHeader: { flexDirection: 'row', alignItems: 'center', padding: 15, borderBottomWidth: 1, borderBottomColor: '#222' },
  modalTitle: { color: '#fff', fontSize: 16, fontWeight: 'bold', flex: 1, marginHorizontal: 10 },
  closeBtn: { padding: 5 },
  fullImageContainer: { flex: 1, backgroundColor: 'rgba(0,0,0,1)', justifyContent: 'center' },
  fullImageWrapper: { width, height: '100%', justifyContent: 'center', alignItems: 'center' },
  fullImage: { width: width, height: '100%' },
  imageCloseBtn: { position: 'absolute', top: 50, right: 20, zIndex: 10, padding: 10, backgroundColor: 'rgba(0,0,0,0.5)', borderRadius: 25 },
  modalOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.7)', justifyContent: 'center', alignItems: 'center' },
  editModal: { width: 300, backgroundColor: '#222', padding: 20, borderRadius: 15 },
  editTitle: { color: '#fff', fontSize: 18, fontWeight: 'bold', marginBottom: 15, textAlign: 'center' },
  editInput: { backgroundColor: '#333', color: '#fff', padding: 10, borderRadius: 8, marginBottom: 20 },
  editButtons: { flexDirection: 'row', justifyContent: 'space-between' },
  editBtnCancel: { flex: 1, padding: 12, marginRight: 5, backgroundColor: '#444', borderRadius: 8, alignItems: 'center' },
  editBtnSave: { flex: 1, padding: 12, marginLeft: 5, backgroundColor: '#fff', borderRadius: 8, alignItems: 'center' },
  editBtnText: { fontWeight: 'bold' }
});
