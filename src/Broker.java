import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.swing.plaf.SliderUI;

import org.apache.log4j.BasicConfigurator;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;

// zookeeper connector
public class Broker implements Watcher {
	static final String AUCTIONS_PATH = "/auctions";
	
	private String host;

	private ZooKeeper zk;
	// a auction e a fila são criadas nos métodos participate e createAuction
	private BidQueue bidQueue;
	private Auction auction;
   	final CountDownLatch connectedSignal = new CountDownLatch(1);

	public boolean connected = true;
	private float lastBid = 0;
	private float currentBid = 0;
	
	public Broker(String host) {
//		BasicConfigurator.configure();
		this.host = host;
	}
	
	// conecta a cria um znode para o cliente
	public ZooKeeper connect() throws Exception {
		zk = new ZooKeeper(host, 60000, new Watcher() {
			@Override
			public void process(WatchedEvent e) {
				if (e.getState() == KeeperState.SyncConnected) {
	               connectedSignal.countDown();
	            }
			}
		});

      	connectedSignal.await();
		
		return zk;
	}

	public List<Auction> getAuctions() throws KeeperException, InterruptedException, ClassNotFoundException, IOException {
		List<String> children = zk.getChildren(AUCTIONS_PATH, false);

		List<Auction> auctions = new ArrayList<Auction>();

		for (String child : children) {
			byte[] data = zk.getData(AUCTIONS_PATH + "/" + child, false, null);
			Auction auction = Converter.fromBytes(data);
			
			if (auction.getStartDate().after(new Date())) {
				// seta o valor pois o id foi gerado depois do nó ser criado
				auction.setId(AUCTIONS_PATH + "/" + child);
				auctions.add(auction);
			}
		}

		return auctions;
	}
	
	// cria um znode sequencial no caminho do produto selecionado
	public void participate(Auctionator auctionator, Auction auction) throws KeeperException, InterruptedException, IOException {
		this.auction = auction;
		bidQueue = new BidQueue(zk, auction);
		
		String auctionatorsPath = auction.getId() + "/auctionators";
		String auctionatorPath = zk.create(auctionatorsPath + "/", Converter.toBytes(auctionator), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
		auctionator.setId(auctionatorPath);
		
		zk.getData(auction.getId() + "/bestbid", this, null);
		
		AuctionBarrier barrier = new AuctionBarrier(auction);
		barrier.enter();
	}

	// cria um znode sequencial para a auction e dois filhos para os auctionators e os bids
	public void createAuction(Auction auction) throws KeeperException, InterruptedException, IOException {
		if (zk.exists(AUCTIONS_PATH, false) == null) 
			zk.create(AUCTIONS_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

		String auctionPath = zk.create(AUCTIONS_PATH + "/", Converter.toBytes(auction), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);

		zk.create(auctionPath + "/bestbid", Converter.toBytes(auction.getStartBid()), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		zk.create(auctionPath + "/auctionators", null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		auction.setId(auctionPath);
		
		this.auction = auction;
		bidQueue = new BidQueue(zk, auction);
		
		AuctionBarrier barrier = new AuctionBarrier(auction);
		barrier.enter();
	}
	
	// watchers
	public void watchAuctions(Auctionator auctionator) throws KeeperException, InterruptedException {
		zk.getChildren(AUCTIONS_PATH, auctionator, null);
	}

	public void bid(Bid bid) throws KeeperException, InterruptedException, ClassNotFoundException, IOException {
		Bid bestBid = Converter.fromBytes(zk.getData(bid.getAuction().getId() + "/bestbid", false, null));
	
		if (bid.getValue() > bestBid.getValue()) {
			bidQueue.add(bid);
		}
	}
	
	public Bid pollBid() throws ClassNotFoundException, KeeperException, InterruptedException, IOException {
		return bidQueue.poll();
	}
	
	public Bid getBestBid() throws ClassNotFoundException, IOException, KeeperException, InterruptedException {
		return Converter.fromBytes(zk.getData(auction.getId() + "/bestbid", false, null));
	}
	
	public void updateBestBid(Bid bid) throws KeeperException, InterruptedException, IOException {
		String bestBidPath = auction.getId() + "/bestbid";
		zk.setData(bestBidPath, Converter.toBytes(bid), zk.exists(bestBidPath, true).getVersion());
	}

	@Override
	public void process(WatchedEvent e) {
		Bid bestBid;
		try {
			bestBid = Converter.fromBytes(zk.getData(auction.getId() + "/bestbid", this, null));
			System.out.printf("Melhor lance: R$ %.2f\n", bestBid.getValue());
		} catch (ClassNotFoundException | IOException | KeeperException | InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}
}
